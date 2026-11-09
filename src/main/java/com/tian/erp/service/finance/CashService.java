package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 在事务中处理资金账户、收付、往来单审核及核销和反核销。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CashService {
    private final FinanceRepository db;
    private final LedgerService ledger;

    /**
     * 注入资金数据访问和凭证生成服务。
     */
    CashService(FinanceRepository db, LedgerService ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    /**
     * 创建银行或现金账户，设置币种、账务科目及授权范围内的初始余额。
     */
    public long addBankAccount(Map<String, Object> body, long actor) {
        var fields = fields(body, "name bank_name account_number currency account_type ledger_account_id opening_balance");
        var type = requiredText(body, "account_type");
        fields.put("name", requiredText(body, "name"));
        if (type.equals("银行账户")) {
            fields.put("account_number", requiredText(body, "account_number"));
            fields.put("bank_name", requiredText(body, "bank_name"));
        } else {
            fields.remove("account_number");
            fields.remove("bank_name");
        }
        fields.put("currency", currency(body));
        db.row("fin_account", id(body, "ledger_account_id"), false);
        var opening = body.get("opening_balance") == null ? BigDecimal.ZERO : money(body, "opening_balance");
        require(opening.signum() >= 0, "初始余额不得为负");
        state(opening.signum() == 0 || AuthService.admin(db.row("fin_user", actor, false)), "设置初始余额需要管理员权限");
        fields.put("opening_balance", opening);
        fields.put("balance", opening);
        long id = db.insert("fin_bank_account", fields);
        db.audit(actor, "funds/bank-accounts", "新增账户", id, null);
        return id;
    }

    /**
     * 更新账户可维护信息，校验账户类型和启停状态。
     */
    public void editBankAccount(long id, Map<String, Object> body, long actor) {
        var old = db.row("fin_bank_account", id, true);
        var fields = fields(body, "name bank_name account_number status");
        if (fields.containsKey("name")) {
            fields.put("name", requiredText(body, "name"));
        }
        if (fields.containsKey("status")) {
            require(Set.of("已启用", "已停用").contains(requiredText(body, "status")), "无效账户状态");
        }
        if ("现金账户".equals(old.get("account_type"))) {
            require(!fields.containsKey("account_number") && !fields.containsKey("bank_name"), "现金账户没有银行资料");
        } else if (fields.containsKey("account_number")) {
            fields.put("account_number", requiredText(body, "account_number"));
        }
        db.patch("fin_bank_account", id, fields);
        db.audit(actor, "funds/bank-accounts", "编辑账户", id, null);
    }

    /**
     * 登记资金单，往来收付款按预收或预付类型直接确认。
     */
    public long addCashOrder(String resource, Map<String, Object> body, long actor) {
        var date = date(body, "transaction_date");
        var bank = db.row("fin_bank_account", id(body, "bank_account_id"), true);
        var period = ledger.period(date);
        unlocked(bank, period);
        var fields = fields(body, "party_id bank_account_id counterpart_account_id transaction_date purpose");
        fields.put("purpose", requiredText(body, "purpose"));
        fields.put("amount", positive(body, "amount"));
        fields.put("exchange_rate", rate(body, bank.get("currency").toString()));
        fields.put("maker_id", actor);
        fields.put("order_no", orderNo(body, "order_no", "CF"));
        String kind = switch (resource) {
            case "ar/collections" -> "AR";
            case "ap/payments" -> "AP";
            default -> "GENERAL";
        };
        String direction = kind.equals("GENERAL") ? requiredText(body, "direction") : kind.equals("AR") ? "收款" : "付款";
        if (!kind.equals("GENERAL")) {
            party(id(body, "party_id"), kind);
        }
        require(id(bank, "ledger_account_id") != id(body, "counterpart_account_id"), "对方科目须与资金账户科目不同");
        db.row("fin_account", id(body, "counterpart_account_id"), false);
        fields.put("direction", direction);
        fields.put("business_type", kind);
        long id = db.insert("fin_cash_order", fields);
        db.audit(actor, resource, "登记收付", id, fields.get("order_no").toString());
        if (!kind.equals("GENERAL")) {
            confirm(id, bank, period, db.row("fin_cash_order", id, true), actor, resource);
        }
        return id;
    }

    /**
     * 要求由其他操作者审核普通收付单，并生成凭证及更新账户余额。
     */
    public long confirmCashOrder(long id, long actor) {
        var snapshot = db.row("fin_cash_order", id, false);
        var bank = db.row("fin_bank_account", id(snapshot, "bank_account_id"), true);
        var period = ledger.period(date(snapshot, "transaction_date"));
        var order = db.row("fin_cash_order", id, true);
        state(id(order, "maker_id") != actor, "制单人不可审核本人收付单");
        return confirm(id, bank, period, order, actor, "funds/cash-orders");
    }

    /**
     * 校验单据与账户期间状态，在同一事务中更新资金余额、凭证关联和确认状态。
     */
    private long confirm(long id, Map<String, Object> bank, Map<String, Object> period, Map<String, Object> order,
                         long actor, String resource) {
        unlocked(bank, period);
        state("待审核".equals(order.get("status")), "仅待审核收付单可确认");
        boolean incoming = "收款".equals(order.get("direction"));
        var amount = decimal(order, "amount");
        var balance = decimal(bank, "balance").add(incoming ? amount : amount.negate());
        state(balance.signum() >= 0, "资金账户余额不足");
        var base = base(amount, decimal(order, "exchange_rate"));
        long bankLedger = id(bank, "ledger_account_id");
        long counterpart = id(order, "counterpart_account_id");
        long voucher = ledger.create(period, date(order, "transaction_date"), "资金收付 " + order.get("order_no"),
                List.of(line(incoming ? bankLedger : counterpart, base, true, order),
                        line(incoming ? counterpart : bankLedger, base, false, order)), actor, null, true);
        db.patch("fin_bank_account", id(bank, "id"), values("balance", balance));
        db.patch("fin_cash_order", id, values("status", "已确认", "voucher_id", voucher));
        db.audit(actor, resource, "确认收付", id, "凭证 " + voucher);
        return voucher;
    }

    /**
     * 校验账户已启用且账户所属期间尚未锁定对账。
     */
    void unlocked(Map<String, Object> bank, Map<String, Object> period) {
        state("已启用".equals(bank.get("status")), "资金账户已停用");
        state(db.rows("""
                SELECT period_id FROM fin_reconciliation_lock WHERE bank_account_id=:bank AND period_id=:period
                """, values("bank", bank.get("id"), "period", period.get("id"))).isEmpty(), "该账户期间已锁定对账");
    }

    /**
     * 录入应收或应付单，校验金额、日期及往来单位，按需记录来源标识。
     */
    public long addTrade(String side, Map<String, Object> body, long actor, boolean sync) {
        LocalDate date = date(body, "order_date");
        require(!date(body, "due_date").isBefore(date), "到期日早于单据日");
        ledger.period(date);
        party(id(body, "party_id"), side);
        var fields = fields(body, "party_id order_date due_date trade_account_id counterpart_account_id tax_account_id department_id project_id source_type source_no");
        long tradeAccount = id(body, "trade_account_id");
        require(tradeAccount != id(body, "counterpart_account_id"), "往来科目和对方科目须不同");
        var amount = positive(body, "amount");
        var tax = body.get("tax_amount") == null ? BigDecimal.ZERO : money(body, "tax_amount");
        require(tax.signum() >= 0 && tax.compareTo(amount) <= 0, "税额须介于零和含税金额之间");
        if (tax.signum() > 0) {
            require(id(body, "tax_account_id") != tradeAccount, "税科目和往来科目须不同");
        }
        fields.put("side", side);
        fields.put("order_no", orderNo(body, "order_no", side));
        fields.put("amount", amount);
        fields.put("tax_amount", tax);
        String currency = currency(body);
        fields.put("currency", currency);
        fields.put("exchange_rate", rate(body, currency));
        fields.put("maker_id", actor);
        if (sync || body.containsKey("source_no") || body.containsKey("source_type")) {
            fields.put("source_type", requiredText(body, "source_type"));
            fields.put("source_no", requiredText(body, "source_no"));
        }
        long id = db.insert("fin_trade", fields);
        db.audit(actor, tradeResource(side), sync ? "同步来源单据" : "新增往来单", id, null);
        return id;
    }

    /**
     * 要求非制单人审核待审核往来单，生成含税额分录的待审核凭证。
     */
    public long approveTrade(String side, long id, long actor) {
        var order = db.row("fin_trade", id, true);
        state(side.equals(order.get("side")) && "待审核".equals(order.get("status")), "往来单类型或状态不匹配");
        state(id(order, "maker_id") != actor, "制单人不可审核本人单据");
        var date = date(order, "order_date");
        var period = ledger.period(date);
        var rate = decimal(order, "exchange_rate");
        var gross = base(decimal(order, "amount"), rate);
        var tax = base(decimal(order, "tax_amount"), rate);
        var net = gross.subtract(tax);
        boolean ar = side.equals("AR");
        var lines = new ArrayList<Map<String, Object>>();
        lines.add(line(id(order, "trade_account_id"), gross, ar, order));
        if (net.signum() > 0) {
            lines.add(line(id(order, "counterpart_account_id"), net, !ar, order));
        }
        if (tax.signum() > 0) {
            lines.add(line(id(order, "tax_account_id"), tax, !ar, order));
        }
        long voucher = ledger.create(period, date, "往来确认 " + order.get("order_no"), lines, actor, null, true);
        db.patch("fin_trade", id, values("status", "已审核", "voucher_id", voucher));
        db.audit(actor, tradeResource(side), "审核往来单", id, "凭证 " + voucher);
        return voucher;
    }

    /**
     * 按往来单位、币种、汇率及可用余额返回候选核销单据。
     */
    public List<Map<String, Object>> suggestions(String side, long cashId) {
        var cash = settlementCash(side, cashId, false);
        var available = decimal(cash, "amount").subtract(usedCash(cashId));
        return db.rows("""
                SELECT t.*,t.amount-t.settled_amount open_amount,
                       LEAST(t.amount-t.settled_amount,:available) suggested_amount
                FROM fin_trade t JOIN fin_bank_account b ON b.id=:bank
                WHERE t.side=:side AND t.party_id=:party AND t.currency=b.currency AND t.exchange_rate=:rate
                  AND t.status IN ('已审核','部分核销') AND t.settled_amount<t.amount AND :available>0
                ORDER BY (t.amount-t.settled_amount=:available) DESC,t.due_date,t.id LIMIT 100
                """, values("side", side, "bank", cash.get("bank_account_id"), "party", cash.get("party_id"),
                "rate", cash.get("exchange_rate"), "available", available));
    }

    /**
     * 锁定资金单与往来单，以 request_key 校验重试，保存核销并更新往来余额。
     */
    public long settle(String side, long cashId, Map<String, Object> body, long actor) {
        var cash = settlementCash(side, cashId, true);
        long tradeId = id(body, "trade_id");
        var trade = db.row("fin_trade", tradeId, true);
        var amount = positive(body, "amount");
        var date = date(body, "settled_on");
        String key = requiredText(body, "request_key");
        var existing = db.rows("SELECT * FROM fin_settlement WHERE request_key=:key", values("key", key));
        if (!existing.isEmpty()) {
            var previous = existing.get(0);
            state(id(previous, "trade_id") == tradeId && id(previous, "cash_order_id") == cashId
                    && date(previous, "settled_on").equals(date) && decimal(previous, "amount").compareTo(amount) == 0,
                    "请求号已经用于另一笔核销");
            return id(previous, "id");
        }
        state(side.equals(trade.get("side")) && Set.of("已审核", "部分核销").contains(trade.get("status")),
                "往来单不在可核销状态");
        require(Objects.equals(cash.get("party_id"), trade.get("party_id")), "双方往来单位不同");
        var bank = db.row("fin_bank_account", id(cash, "bank_account_id"), false);
        var rate = decimal(cash, "exchange_rate");
        require(Objects.equals(bank.get("currency"), trade.get("currency")) && rate.compareTo(decimal(trade, "exchange_rate")) == 0,
                "核销要求同币种同汇率");
        require(!date.isBefore(date(trade, "order_date")) && !date.isBefore(date(cash, "transaction_date")), "核销日期早于原单");
        var newTotal = decimal(trade, "settled_amount").add(amount);
        state(newTotal.compareTo(decimal(trade, "amount")) <= 0
                && usedCash(cashId).add(amount).compareTo(decimal(cash, "amount")) <= 0, "核销金额超过剩余余额");
        long counterpart = id(cash, "counterpart_account_id");
        long ledgerAccount = id(trade, "trade_account_id");
        require(counterpart != ledgerAccount, "预收预付款科目须与往来科目不同");
        var period = ledger.period(date);
        var base = base(amount, rate);
        boolean ar = side.equals("AR");
        long voucher = ledger.create(period, date, "往来核销 " + trade.get("order_no"),
                List.of(line(counterpart, base, ar, trade), line(ledgerAccount, base, !ar, trade)), actor, null, true);
        long id = db.insert("fin_settlement", values("trade_id", tradeId, "cash_order_id", cashId, "amount", amount,
                "settled_on", date, "voucher_id", voucher, "request_key", key));
        updateTradeBalance(trade, newTotal);
        db.audit(actor, cashResource(side), "核销", id, "凭证 " + voucher);
        return id;
    }

    /**
     * 恢复原核销金额并关联红字冲销凭证，要求原核销凭证已记账。
     */
    public long reverseSettlement(String side, long cashId, Map<String, Object> body, long actor) {
        settlementCash(side, cashId, true);
        long settlementId = id(body, "settlement_id");
        var settlement = db.row("fin_settlement", settlementId, true);
        state(id(settlement, "cash_order_id") == cashId && settlement.get("reversed_on") == null, "核销已冲销或不属于该单据");
        var trade = db.row("fin_trade", id(settlement, "trade_id"), true);
        state(side.equals(trade.get("side")), "往来类型不匹配");
        var date = date(body, "reversed_on");
        var originalDate = date(settlement, "settled_on");
        var originalPeriod = ledger.period(originalDate);
        var original = db.row("fin_voucher", id(settlement, "voucher_id"), true);
        state("已记账".equals(original.get("status")), "原核销凭证尚未记账");
        long voucher = ledger.reverse(original, originalPeriod, date, actor);
        db.patch("fin_settlement", settlementId, values("reversed_on", date, "reversal_voucher_id", voucher));
        updateTradeBalance(trade, decimal(trade, "settled_amount").subtract(decimal(settlement, "amount")));
        db.audit(actor, cashResource(side), "反核销", settlementId, "凭证 " + voucher);
        return voucher;
    }

    /**
     * 读取核销使用的资金单，校验业务方向及已确认状态，可加行锁。
     */
    private Map<String, Object> settlementCash(String side, long cashId, boolean lock) {
        var cash = db.row("fin_cash_order", cashId, lock);
        state(side.equals(cash.get("business_type")) && "已确认".equals(cash.get("status")), "单据不是已确认的预收或预付款");
        return cash;
    }

    /**
     * 汇总资金单当前未反核销的累计分配金额。
     */
    private BigDecimal usedCash(long id) {
        return decimal(db.one("""
                SELECT COALESCE(SUM(amount),0) used_amount FROM fin_settlement WHERE cash_order_id=:id AND reversed_on IS NULL
                """, values("id", id)), "used_amount");
    }

    /**
     * 校验已核销总额并同步待核销、部分核销或已核销状态。
     */
    void updateTradeBalance(Map<String, Object> trade, BigDecimal settled) {
        state(settled.signum() >= 0 && settled.compareTo(decimal(trade, "amount")) <= 0, "余额更新越界");
        String status = settled.signum() == 0 ? "已审核"
                : settled.compareTo(decimal(trade, "amount")) == 0 ? "已核销" : "部分核销";
        db.patch("fin_trade", id(trade, "id"), values("settled_amount", settled, "status", status));
    }

    /**
     * 校验往来单位与 AR 或 AP 业务的类型及启用状态一致。
     */
    private void party(long party, String side) {
        var row = db.row("fin_master", party, false);
        require((side.equals("AR") ? "客户" : "供应商").equals(row.get("data_type")) && "已启用".equals(row.get("status")),
                "往来单位类型或状态不匹配");
    }

    /**
     * 按科目、金额和借贷方向建立分录并复制适用的辅助维度。
     */
    static Map<String, Object> line(long account, BigDecimal amount, boolean debit, Map<String, Object> dimensions) {
        var row = fields(dimensions, "party_id department_id project_id");
        row.put("account_id", account);
        row.put(debit ? "debit" : "credit", amount);
        return row;
    }

    /**
     * 读取正数金额并校验两位小数精度。
     */
    static BigDecimal positive(Map<String, Object> body, String field) {
        var amount = money(body, field);
        require(amount.signum() > 0, field + " 必须大于零");
        return amount;
    }

    /**
     * 将原币金额按汇率换算为两位小数的本位币金额。
     */
    static BigDecimal base(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 校验汇率为正数且最多八位小数，CNY 汇率固定为 1。
     */
    private BigDecimal rate(Map<String, Object> body, String currency) {
        var rate = body.get("exchange_rate") == null && currency.equals("CNY") ? BigDecimal.ONE : decimal(body, "exchange_rate");
        require(rate.signum() > 0 && rate.stripTrailingZeros().scale() <= 8
                && (!currency.equals("CNY") || rate.compareTo(BigDecimal.ONE) == 0), "汇率无效");
        return rate;
    }

    /**
     * 读取币种，缺省使用 CNY，并校验三位大写代码。
     */
    private String currency(Map<String, Object> body) {
        String currency = body.get("currency") == null ? "CNY" : requiredText(body, "currency");
        require(currency.matches("[A-Z]{3}"), "币种须为三位大写代码");
        return currency;
    }

    /**
     * 使用调用方提供的非空业务号，缺省时用前缀和 UUID 生成业务号。
     */
    static String orderNo(Map<String, Object> body, String field, String prefix) {
        return body.get(field) == null ? prefix + "-" + UUID.randomUUID() : requiredText(body, field);
    }

    /**
     * 将往来方向映射到应收单或应付单资源标识。
     */
    static String tradeResource(String side) {
        return side.equals("AR") ? "ar/receivables" : "ap/payables";
    }

    /**
     * 将往来方向映射到收款核销或付款核销资源标识。
     */
    static String cashResource(String side) {
        return side.equals("AR") ? "ar/collections" : "ap/payments";
    }
}
