package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.common.exception.BusinessException.unsupported;
import static com.tian.erp.service.finance.CashService.*;
import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 分派专项写操作，并处理票据、资金计划和坏账单据。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FinanceOperationService {
    private final FinanceRepository db;
    private final LedgerService ledger;
    private final CashService cash;
    private final FinanceSystemService system;
    private final ExpenseService expense;
    private final AssetService assets;
    private final ReconciliationService reconciliation;

    /**
     * 注入专项操作的持久化和各财务业务服务。
     */
    FinanceOperationService(FinanceRepository db, LedgerService ledger, CashService cash, FinanceSystemService system,
               ExpenseService expense, AssetService assets, ReconciliationService reconciliation) {
        this.db = db;
        this.ledger = ledger;
        this.cash = cash;
        this.system = system;
        this.expense = expense;
        this.assets = assets;
        this.reconciliation = reconciliation;
    }

    /**
     * 将专项资源创建请求分派到票据、计划、费用、资产或坏账逻辑。
     */
    public long create(String resource, Map<String, Object> body, long actor) {
        return switch (resource) {
            case "ap/notes" -> note(body, actor);
            case "funds/cash-plans" -> plan(body, actor);
            case "expense/claims" -> expense.claim(body, actor);
            case "expense/loans" -> expense.loan(body, actor);
            case "fa/asset-cards", "fa/asset-movements", "fa/asset-disposals" -> assets.create(resource, body, actor);
            case "fa/depreciation" -> assets.depreciate(body, actor);
            case "ar/bad-debts" -> badDebt(body, actor);
            default -> throw unsupported();
        };
    }

    /**
     * 按资源分派专项行操作，并执行借款操作所需的管理员校验。
     */
    public Long action(String resource, long id, String action, Map<String, Object> body, Map<String, Object> user) {
        long actor = id(user, "id");
        return switch (resource) {
            case "ap/notes" -> {
                require(action.equals("redeem"), "未知票据操作");
                redeem(id, body, actor);
                yield null;
            }
            case "funds/reconciliation" -> {
                require(action.equals("match"), "未知对账操作");
                reconciliation.match(id, body, actor);
                yield null;
            }
            case "expense/claims" -> {
                require(action.equals("submit"), "未知报销操作");
                expense.submit(id, actor);
                yield null;
            }
            case "expense/loans" -> {
                require(AuthService.admin(user), "借款确认及还款登记需要管理员权限");
                expense.loanAction(id, action, body, actor);
                yield null;
            }
            case "expense/approvals" -> {
                expense.handle(id, action, body, actor);
                yield null;
            }
            case "expense/expense-payments" -> {
                require(action.equals("pay"), "未知报销支付操作");
                yield expense.pay(id, body, actor);
            }
            case "fa/asset-movements" -> {
                require(action.equals("confirm"), "未知资产变动操作");
                assets.confirmMovement(id, body, actor);
                yield null;
            }
            case "fa/asset-disposals" -> {
                require(action.equals("confirm"), "未知资产处置操作");
                yield assets.confirmDisposal(id, body, actor);
            }
            case "fa/depreciation" -> {
                require(action.equals("adjust"), "未知折旧操作");
                yield assets.adjust(id, body, actor);
            }
            default -> throw unsupported();
        };
    }

    /**
     * 校验供应商、票面金额及日期后登记应付票据。
     */
    private long note(Map<String, Object> body, long actor) {
        system.master(id(body, "party_id"), "供应商");
        var date = date(body, "issued_on");
        var due = date(body, "due_date");
        require(!due.isBefore(date), "票据到期日早于签发日");
        ledger.period(date);
        long id = db.insert("fin_note", values("note_no", requiredText(body, "note_no"), "party_id", id(body, "party_id"),
                "note_type", requiredText(body, "note_type"), "amount", positive(body, "amount"), "issued_on", date, "due_date", due));
        db.audit(actor, "ap/notes", "登记票据", id, null);
        return id;
    }

    /**
     * 将票据关联到金额和往来单位匹配的已确认普通付款单并标记兑付。
     */
    private void redeem(long id, Map<String, Object> body, long actor) {
        var note = db.row("fin_note", id, true);
        state("已签发".equals(note.get("status")), "票据已兑付");
        long cashId = id(body, "cash_order_id");
        var payment = db.row("fin_cash_order", cashId, true);
        state("已确认".equals(payment.get("status")) && "付款".equals(payment.get("direction"))
                && "GENERAL".equals(payment.get("business_type")) && Objects.equals(payment.get("party_id"), note.get("party_id"))
                && decimal(payment, "amount").compareTo(decimal(note, "amount")) == 0
                && !date(payment, "transaction_date").isBefore(date(note, "issued_on")), "关联付款与票据不匹配");
        db.patch("fin_note", id, values("status", "已兑付", "cash_order_id", cashId));
        db.audit(actor, "ap/notes", "登记兑付", id, "付款 " + cashId);
    }

    /**
     * 校验资金账户、日期和预计收付额后保存周度或月度资金计划。
     */
    private long plan(Map<String, Object> body, long actor) {
        var start = date(body, "start_date");
        var end = date(body, "end_date");
        String cycle = requiredText(body, "cycle");
        require(!end.isBefore(start) && Set.of("周度", "月度").contains(cycle), "无效计划日期或周期");
        var bank = db.row("fin_bank_account", id(body, "bank_account_id"), false);
        state("已启用".equals(bank.get("status")), "资金账户已停用");
        var receipts = money(body, "planned_receipts");
        var payments = money(body, "planned_payments");
        require(receipts.signum() >= 0 && payments.signum() >= 0, "计划收付款不可为负");
        long id = db.insert("fin_cash_plan", values("bank_account_id", bank.get("id"), "cycle", cycle,
                "start_date", start, "end_date", end, "planned_receipts", receipts, "planned_payments", payments));
        db.audit(actor, "funds/cash-plans", "编制资金计划", id, null);
        return id;
    }

    /**
     * 按指定日期读取期初加历史收付净额及当日计划，计算预计余额。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> position(Map<String, Object> query) {
        var date = date(query, "date");
        return db.rows("""
                SELECT b.id,b.name,b.currency,
                  b.opening_balance+COALESCE(m.net,0) book_balance,COALESCE(p.receipts,0) planned_receipts,
                  COALESCE(p.payments,0) planned_payments,
                  b.opening_balance+COALESCE(m.net,0)+COALESCE(p.receipts,0)-COALESCE(p.payments,0) projected_balance
                FROM fin_bank_account b
                LEFT JOIN (
                    SELECT bank_account_id,SUM(IF(direction='收款',amount,-amount)) net FROM fin_cash_order
                    WHERE transaction_date<:date AND status='已确认' GROUP BY bank_account_id
                ) m ON m.bank_account_id=b.id
                LEFT JOIN (
                    SELECT bank_account_id,SUM(planned_receipts) receipts,SUM(planned_payments) payments FROM fin_cash_plan
                    WHERE start_date=:date AND end_date=:date GROUP BY bank_account_id
                ) p ON p.bank_account_id=b.id
                WHERE b.status='已启用' AND (:account IS NULL OR b.id=:account) ORDER BY b.id
                """, values("date", date, "account", query.get("bank_account_id")));
    }

    /**
     * 按计提、核销或收回方式生成凭证并保存坏账记录，校验相关余额上限。
     */
    private long badDebt(Map<String, Object> body, long actor) {
        var trade = db.row("fin_trade", id(body, "trade_id"), true);
        state("AR".equals(trade.get("side")) && trade.get("voucher_id") != null, "须引用已审核应收单");
        var date = date(body, "transaction_date");
        require(!date.isBefore(date(trade, "order_date")), "业务日期早于应收单");
        var period = ledger.period(date);
        String treatment = requiredText(body, "treatment");
        BigDecimal amount = positive(body, "amount");
        var base = base(amount, decimal(trade, "exchange_rate"));
        long debit;
        long credit;
        Long original = null;
        switch (treatment) {
            case "计提" -> {
                state(amount.compareTo(decimal(trade, "amount").subtract(decimal(trade, "settled_amount"))) <= 0, "计提额超过未核销额");
                var booked = db.one("""
                        SELECT COALESCE(SUM(amount),0) total FROM fin_bad_debt WHERE trade_id=:trade
                          AND period_id=:period AND treatment='计提'
                        """, values("trade", trade.get("id"), "period", period.get("id")));
                state(decimal(booked, "total").signum() == 0, "本期已计提");
                debit = id(body, "expense_account_id");
                credit = id(body, "allowance_account_id");
            }
            case "核销" -> {
                var total = decimal(trade, "settled_amount").add(amount);
                cash.updateTradeBalance(trade, total);
                debit = id(body, "allowance_account_id");
                credit = id(trade, "trade_account_id");
            }
            case "收回" -> {
                original = id(body, "original_id");
                var writeOff = db.row("fin_bad_debt", original, true);
                state("核销".equals(writeOff.get("treatment")) && Objects.equals(writeOff.get("trade_id"), trade.get("id"))
                        && !date.isBefore(date(writeOff, "transaction_date")), "须关联该应收单的历史核销");
                var recovered = db.one("SELECT COALESCE(SUM(amount),0) total FROM fin_bad_debt WHERE original_id=:id", values("id", original));
                state(decimal(recovered, "total").add(amount).compareTo(decimal(writeOff, "amount")) <= 0, "收回金额超过核销额");
                debit = id(body, "recovery_account_id");
                credit = id(body, "allowance_account_id");
            }
            default -> throw unsupported();
        }
        require(debit != credit, "借贷科目必须不同");
        String number = orderNo(body, "order_no", "HZ");
        long voucher = ledger.create(period, date, "坏账" + treatment + " " + number,
                List.of(line(debit, base, true, trade), line(credit, base, false, trade)), actor, null, true);
        long id = db.insert("fin_bad_debt", values("trade_id", trade.get("id"), "order_no", number, "original_id", original,
                "period_id", period.get("id"), "transaction_date", date, "treatment", treatment, "amount", amount, "voucher_id", voucher));
        db.audit(actor, "ar/bad-debts", treatment, id, "凭证 " + voucher);
        return id;
    }
}
