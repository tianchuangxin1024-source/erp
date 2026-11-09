package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.service.finance.CashService.*;
import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 处理银行流水导入、自动或人工匹配、余额调节及对账锁定。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReconciliationService {
    private final FinanceRepository db;
    private final LedgerService ledger;
    private final CashService cash;

    /**
     * 注入银行流水存储、会计期间和资金状态校验服务。
     */
    ReconciliationService(FinanceRepository db, LedgerService ledger, CashService cash) {
        this.db = db;
        this.ledger = ledger;
        this.cash = cash;
    }

    /**
     * 按账户、日期和流水号去重导入，重复号内容不一致时回滚整批。
     */
    public Map<String, Object> loadStatements(Map<String, Object> body, long actor) {
        long bankId = id(body, "bank_account_id");
        var bank = db.row("fin_bank_account", bankId, true);
        var lines = items(body, "lines", 1000);
        var periods = new HashMap<String, Map<String, Object>>();
        int inserted = 0;
        for (var line : lines) {
            var date = date(line, "transaction_date");
            var period = periods.computeIfAbsent(java.time.YearMonth.from(date).toString(), ignored -> ledger.period(date));
            cash.unlocked(bank, period);
            String reference = requiredText(line, "reference_no");
            var key = values("bank", bankId, "date", date, "ref", reference);
            var old = db.rows("""
                    SELECT * FROM fin_bank_statement WHERE bank_account_id=:bank AND transaction_date=:date AND reference_no=:ref
                    """, key);
            var amount = positive(line, "amount");
            String direction = requiredText(line, "direction");
            String summary = requiredText(line, "summary");
            require(Set.of("收款", "付款").contains(direction), "银行流水方向无效");
            if (!old.isEmpty()) {
                var existing = old.get(0);
                state(decimal(existing, "amount").compareTo(amount) == 0 && direction.equals(existing.get("direction"))
                        && summary.equals(existing.get("summary")), "同一流水号存在不同记录: " + reference);
                continue;
            }
            db.insert("fin_bank_statement", values("bank_account_id", bankId, "transaction_date", date,
                    "reference_no", reference, "direction", direction, "amount", amount, "summary", summary));
            inserted++;
        }
        db.audit(actor, "funds/reconciliation", "导入银行流水", bankId, "新增 " + inserted + " 行");
        return values("inserted", inserted, "duplicates", lines.size() - inserted);
    }

    /**
     * 将单条未匹配银行流水与同账户、方向、金额及期间的已确认资金单勾对。
     */
    void match(long id, Map<String, Object> body, long actor) {
        var statement = db.row("fin_bank_statement", id, false);
        var bank = db.row("fin_bank_account", id(statement, "bank_account_id"), true);
        var period = ledger.period(date(statement, "transaction_date"));
        cash.unlocked(bank, period);
        statement = db.row("fin_bank_statement", id, true);
        state(statement.get("matched_order_id") == null, "银行流水已匹配");
        var order = db.row("fin_cash_order", id(body, "cash_order_id"), true);
        state("已确认".equals(order.get("status")) && Objects.equals(order.get("bank_account_id"), bank.get("id"))
                && Objects.equals(order.get("direction"), statement.get("direction"))
                && decimal(order, "amount").compareTo(decimal(statement, "amount")) == 0
                && !date(order, "transaction_date").isBefore(date(period, "start_date"))
                && !date(order, "transaction_date").isAfter(date(period, "end_date")), "账面单据不满足勾对条件");
        db.patch("fin_bank_statement", id, values("matched_order_id", order.get("id"), "match_method", "手工"));
        db.audit(actor, "funds/reconciliation", "手工勾对", id, null);
    }

    /**
     * 用日期、金额、方向及摘要匹配流水，仅保存一对一候选并返回冲突标识。
     */
    public Map<String, Object> automatic(Map<String, Object> body, long actor) {
        var bank = db.row("fin_bank_account", id(body, "bank_account_id"), true);
        var period = lockPeriod(body);
        cash.unlocked(bank, period);
        var params = values("bank", bank.get("id"), "start", period.get("start_date"), "end", period.get("end_date"));
        var pairs = db.rows("""
                SELECT s.id statement_id,o.id order_id
                FROM fin_bank_statement s JOIN fin_cash_order o ON o.bank_account_id=s.bank_account_id
                  AND o.transaction_date=s.transaction_date AND o.direction=s.direction AND o.amount=s.amount
                  AND (LOCATE(s.summary,o.purpose)>0 OR LOCATE(o.purpose,s.summary)>0)
                WHERE s.bank_account_id=:bank AND s.transaction_date BETWEEN :start AND :end
                  AND s.matched_order_id IS NULL AND o.status='已确认'
                  AND NOT EXISTS(SELECT 1 FROM fin_bank_statement m WHERE m.matched_order_id=o.id)
                """, params);
        var statementCounts = new HashMap<Long, Integer>();
        var orderCounts = new HashMap<Long, Integer>();
        for (var pair : pairs) {
            statementCounts.merge(id(pair, "statement_id"), 1, Integer::sum);
            orderCounts.merge(id(pair, "order_id"), 1, Integer::sum);
        }
        int matched = 0;
        var conflicts = new LinkedHashSet<Long>();
        for (var pair : pairs) {
            long statement = id(pair, "statement_id");
            long order = id(pair, "order_id");
            if (statementCounts.get(statement) == 1 && orderCounts.get(order) == 1) {
                db.patch("fin_bank_statement", statement, values("matched_order_id", order, "match_method", "自动"));
                matched++;
            } else {
                conflicts.add(statement);
            }
        }
        db.audit(actor, "funds/reconciliation", "自动匹配", id(bank, "id"), "匹配 " + matched + " 行");
        return values("matched", matched, "conflict_ids", conflicts);
    }

    /**
     * 根据银行期末余额、历史账面收付及本期间未达项计算调节后余额。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> adjustment(Map<String, Object> body) {
        var bank = db.row("fin_bank_account", id(body, "bank_account_id"), false);
        var period = db.row("fin_period", id(body, "period_id"), false);
        var bankBalance = money(body, "bank_ending_balance");
        var params = values("bank", bank.get("id"), "start", period.get("start_date"), "end", period.get("end_date"));
        var movements = db.one("""
                SELECT COALESCE(SUM(IF(direction='收款',amount,-amount)),0) book_movements
                FROM fin_cash_order WHERE bank_account_id=:bank AND transaction_date<=:end AND status='已确认'
                """, params);
        var bankOnly = db.rows("""
                SELECT id,reference_no,transaction_date,direction,amount FROM fin_bank_statement
                WHERE bank_account_id=:bank AND transaction_date BETWEEN :start AND :end AND matched_order_id IS NULL
                ORDER BY transaction_date,id
                """, params);
        var bookOnly = db.rows("""
                SELECT c.id,c.order_no,c.transaction_date,c.direction,c.amount FROM fin_cash_order c
                WHERE c.bank_account_id=:bank AND c.transaction_date BETWEEN :start AND :end AND c.status='已确认'
                  AND NOT EXISTS (SELECT 1 FROM fin_bank_statement s WHERE s.matched_order_id=c.id)
                ORDER BY c.transaction_date,c.id
                """, params);
        var bookBalance = decimal(bank, "opening_balance").add(decimal(movements, "book_movements"));
        var adjustedBank = bankBalance.add(net(bookOnly));
        var adjustedBook = bookBalance.add(net(bankOnly));
        return values("bank_balance", bankBalance, "book_balance", bookBalance, "adjusted_bank", adjustedBank,
                "adjusted_book", adjustedBook, "difference", adjustedBank.subtract(adjustedBook),
                "bank_only", bankOnly, "book_only", bookOnly, "currency", bank.get("currency"));
    }

    /**
     * 要求余额调节差额为零后保存账户与期间的对账锁定记录。
     */
    public void lock(Map<String, Object> body, long actor) {
        var bank = db.row("fin_bank_account", id(body, "bank_account_id"), true);
        var period = lockPeriod(body);
        cash.unlocked(bank, period);
        var result = adjustment(body);
        state(decimal(result, "difference").signum() == 0, "调节后余额不一致");
        db.update("INSERT INTO fin_reconciliation_lock(bank_account_id,period_id,locked_by) VALUES (:bank,:period,:actor)",
                values("bank", bank.get("id"), "period", period.get("id"), "actor", actor));
        db.audit(actor, "funds/reconciliation", "锁定对账期间", id(bank, "id"), period.get("period_code").toString());
    }

    /**
     * 读取并锁定指定的开放会计期间。
     */
    private Map<String, Object> lockPeriod(Map<String, Object> body) {
        var period = db.row("fin_period", id(body, "period_id"), true);
        state("进行中".equals(period.get("status")), "会计期间已结账");
        return period;
    }

    /**
     * 按收款为正、付款为负计算流水净额。
     */
    private BigDecimal net(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> "收款".equals(row.get("direction")) ? decimal(row, "amount") : decimal(row, "amount").negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
