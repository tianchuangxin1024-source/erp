package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 提供账簿、账龄、工作台、报表取数及期末检查与结账操作。
 */
@Service
@Transactional(readOnly = true)
public class FinanceAnalyticsService {
    private final FinanceRepository db;
    private final LedgerService ledger;

    private final FinanceRuleService rules;

    /**
     * 注入分析查询、凭证服务和财务规则读取组件。
     */
    FinanceAnalyticsService(FinanceRepository db, LedgerService ledger, FinanceRuleService rules) {
        this.db = db;
        this.ledger = ledger;
        this.rules = rules;
    }

    /**
     * 将受支持的分析资源分派到对应的账簿、账龄或报表查询。
     */
    public Map<String, Object> query(String resource, Map<String, String> query) {
        return switch (resource) {
            case "gl/ledgers" -> ledgers(query);
            case "ar/ar-aging" -> aging("AR", query);
            case "ap/ap-aging" -> aging("AP", query);
            case "reports/balance-sheet", "reports/income-statement", "reports/cash-flow" ->
                    report(resource.substring(8), query);
            default -> throw new IllegalArgumentException("Unknown derived resource " + resource);
        };
    }

    /**
     * 汇总已记账分录或返回明细账，校验期间顺序及明细数量限制。
     */
    private Map<String, Object> ledgers(Map<String, String> query) {
        var from = month(query.get("start_period")).atDay(1);
        var to = month(query.getOrDefault("end_period", query.get("start_period"))).atEndOfMonth();
        require(!to.isBefore(from), "期间范围无效");
        var params = values("start", from, "end", to, "level", query.get("account_level"), "account", query.get("account_id"),
                "department", query.get("department_id"), "project", query.get("project_id"), "party", query.get("party_id"));
        String type = query.getOrDefault("ledger_type", "科目余额表");
        if (Set.of("明细账", "多栏账").contains(type)) {
            var rows = db.rows("""
                    SELECT l.*,v.voucher_no,v.voucher_date,v.summary voucher_summary,a.code account_code,a.name account_name
                    FROM fin_voucher_line l JOIN fin_voucher v ON v.id=l.voucher_id JOIN fin_account a ON a.id=l.account_id
                    WHERE v.status='已记账' AND v.voucher_date BETWEEN :start AND :end
                      AND (:account IS NULL OR l.account_id=:account) AND (:level IS NULL OR a.account_level=:level)
                      AND (:department IS NULL OR l.department_id=:department)
                      AND (:project IS NULL OR l.project_id=:project) AND (:party IS NULL OR l.party_id=:party)
                    ORDER BY v.voucher_date,v.id,l.line_no LIMIT 2001
                    """, params);
            require(rows.size() <= 2000, "明细超过 2000 行，请缩小范围");
            return values("items", rows, "total", rows.size());
        }
        require(Set.of("科目余额表", "总账", "辅助核算余额表").contains(type), "无效账簿类型");
        var rows = db.rows("""
                SELECT q.*, opening_balance+IF(balance_side='借', debit-credit, credit-debit) closing_balance
                FROM (
                  SELECT a.id account_id,a.code account_code,a.name account_name,a.balance_side,
                         COALESCE(SUM(CASE WHEN t.voucher_date<:start THEN IF(a.balance_side='借',t.debit-t.credit,t.credit-t.debit) END),0) opening_balance,
                         COALESCE(SUM(CASE WHEN t.voucher_date>=:start THEN t.debit END),0) debit,
                         COALESCE(SUM(CASE WHEN t.voucher_date>=:start THEN t.credit END),0) credit
                  FROM fin_account a LEFT JOIN (
                      SELECT l.*,v.voucher_date FROM fin_voucher_line l JOIN fin_voucher v ON v.id=l.voucher_id
                      WHERE v.status='已记账' AND v.voucher_date<=:end
                        AND (:department IS NULL OR l.department_id=:department)
                        AND (:project IS NULL OR l.project_id=:project) AND (:party IS NULL OR l.party_id=:party)
                  ) t ON t.account_id=a.id
                  WHERE (:level IS NULL OR a.account_level=:level) AND (:account IS NULL OR a.id=:account)
                  GROUP BY a.id,a.code,a.name,a.balance_side
                ) q ORDER BY account_code
                """, params);
        return values("items", rows, "total", rows.size());
    }

    /**
     * 按截止日恢复有效核销与坏账核销金额，按客户或供应商及币种汇总账龄。
     */
    private Map<String, Object> aging(String side, Map<String, String> query) {
        var cutoff = query.containsKey("cutoff_date") ? date(values("date", query.get("cutoff_date")), "date") : LocalDate.now();
        var bounds = rules.agingBounds();
        var rows = db.rows("""
                WITH cutoff_settlements AS (
                    SELECT trade_id,SUM(amount) settled FROM fin_settlement
                    WHERE settled_on<=:cutoff AND (reversed_on IS NULL OR reversed_on>:cutoff) GROUP BY trade_id
                ), open_items AS (
                    SELECT t.id,t.party_id,t.order_no,t.currency,t.due_date,
                           t.amount-COALESCE(s.settled,0)-COALESCE((SELECT SUM(d.amount) FROM fin_bad_debt d
                             WHERE d.trade_id=t.id AND d.treatment='核销' AND d.transaction_date<=:cutoff),0) open_amount,DATEDIFF(:cutoff,t.due_date) days_due
                    FROM fin_trade t LEFT JOIN cutoff_settlements s ON s.trade_id=t.id
                    WHERE t.side=:side AND t.order_date<=:cutoff AND t.voucher_id IS NOT NULL
                      AND (:party IS NULL OR t.party_id=:party) AND (:account IS NULL OR t.trade_account_id=:account)
                      AND (:department IS NULL OR t.department_id=:department) AND (:project IS NULL OR t.project_id=:project)
                )
                SELECT m.id party_id,m.name party,i.currency,SUM(i.open_amount) open_amount,
                    SUM(IF(i.days_due<0,i.open_amount,0)) not_due,
                    SUM(IF(i.days_due BETWEEN 0 AND :b1,i.open_amount,0)) bucket_1,
                    SUM(IF(i.days_due > :b1 AND i.days_due <= :b2,i.open_amount,0)) bucket_2,
                    SUM(IF(i.days_due > :b2 AND i.days_due <= :b3,i.open_amount,0)) bucket_3,
                    SUM(IF(i.days_due>:b3,i.open_amount,0)) bucket_4
                FROM open_items i JOIN fin_master m ON m.id=i.party_id WHERE i.open_amount>0
                GROUP BY m.id,m.name,i.currency ORDER BY m.name,i.currency
                """, values("side", side, "cutoff", cutoff, "party", query.get("party_id"), "b1", bounds.get(0), "b2", bounds.get(1), "b3", bounds.get(2),
                "account", query.get("account_id"), "department", query.get("department_id"), "project", query.get("project_id")));
        return values("cutoff_date", cutoff, "bounds", bounds, "items", rows, "total", rows.size());
    }

    /**
     * 读取期间信息、当前资金余额、收付趋势及调用者可见的待办和近期活动。
     */
    public Map<String, Object> dashboard(Map<String, String> query, Map<String, Object> actor) {
        YearMonth selected = query.containsKey("period_code") ? month(query.get("period_code")) : YearMonth.now();
        var current = db.rows("SELECT id,period_code,start_date,end_date,closed_date,status FROM fin_period WHERE period_code=:code",
                values("code", selected.toString()));
        var params = values("start", selected.minusMonths(5).atDay(1), "end", selected.atEndOfMonth(),
                "account", query.get("bank_account_id"), "currency", query.get("currency"),
                "actor", actor.get("id"), "admin", AuthService.admin(actor), "role", actor.get("role_id"));
        var trends = db.rows("""
                SELECT DATE_FORMAT(c.transaction_date,'%Y-%m') period_code,b.currency,
                       SUM(IF(c.direction='收款',c.amount,0)) receipts,
                       SUM(IF(c.direction='付款',c.amount,0)) payments,
                       SUM(IF(c.direction='收款',c.amount,-c.amount)) net
                FROM fin_cash_order c JOIN fin_bank_account b ON b.id=c.bank_account_id
                WHERE c.status='已确认' AND c.transaction_date BETWEEN :start AND :end
                  AND (:account IS NULL OR b.id=:account) AND (:currency IS NULL OR b.currency=:currency)
                GROUP BY period_code,b.currency ORDER BY period_code,b.currency
                """, params);
        var balances = db.rows("""
                SELECT currency,SUM(balance) balance FROM fin_bank_account
                WHERE status='已启用' AND (:account IS NULL OR id=:account) AND (:currency IS NULL OR currency=:currency)
                GROUP BY currency ORDER BY currency
                """, params);
        var tasks = db.rows("""
                SELECT 'gl/vouchers' resource,COUNT(*) total FROM fin_voucher WHERE status='待审核' AND maker_id<>:actor
                  AND (:admin=TRUE OR EXISTS (SELECT 1 FROM fin_permission WHERE role_id=:role AND resource='gl/vouchers' AND can_write=TRUE))
                UNION ALL SELECT 'expense/approvals',COUNT(*) FROM fin_approval_task WHERE status='待审批' AND assignee_id=:actor
                UNION ALL SELECT 'ap/payables',COUNT(*) FROM fin_trade WHERE side='AP' AND status IN ('待审核','已审核','部分核销')
                  AND (:admin=TRUE OR EXISTS (SELECT 1 FROM fin_permission WHERE role_id=:role AND resource='ap/payables'))
                """, params);
        var activities = db.rows("""
                SELECT id,operated_at,operation,resource,object_id FROM fin_audit a WHERE resource NOT LIKE 'system/%'
                  AND (:admin=TRUE OR (operator_id=:actor AND EXISTS (
                    SELECT 1 FROM fin_permission WHERE role_id=:role AND resource=a.resource)))
                ORDER BY id DESC LIMIT 15
                """, params);
        return values("period", current.isEmpty() ? null : current.get(0), "balances", balances,
                "monthly", trends.stream().filter(row -> selected.toString().equals(row.get("period_code"))).toList(),
                "trend", trends, "tasks", tasks, "activities", activities);
    }

    /**
     * 按模板配置的科目和指标汇总已记账分录，返回本期、累计及对比金额。
     */
    public Map<String, Object> report(String type, Map<String, String> query) {
        var period = month(query.get("period_code"));
        var ids = db.rows("""
                SELECT id,name FROM fin_report WHERE report_type=:type AND status='已启用'
                  AND (:id IS NULL OR id=:id) ORDER BY id LIMIT 2
                """, values("type", type, "id", query.get("report_id")));
        state(ids.size() == 1, "请配置报表公式并指定唯一模板");
        var report = ids.get(0);
        var comparison = query.getOrDefault("comparison", "环比");
        require(Set.of("同比", "环比").contains(comparison), "无效对比方式");
        var previous = comparison.equals("同比") ? period.minusYears(1) : period.minusMonths(1);
        var rows = db.rows("""
                SELECT l.line_no,l.label,
                    ROUND(SUM(IF(t.voucher_date BETWEEN :start AND :end OR (t.voucher_date<=:end AND l.metric='期末余额'),t.value,0)),2) current_amount,
                    ROUND(SUM(IF(t.voucher_date BETWEEN :year_start AND :end OR (t.voucher_date<:year_start AND l.metric='期末余额'),t.value,0)),2) year_to_date,
                    ROUND(SUM(IF(t.voucher_date BETWEEN :previous_start AND :previous_end OR (t.voucher_date<=:previous_end AND l.metric='期末余额'),t.value,0)),2) comparison_amount,
                    ROUND(SUM(IF(t.voucher_date<:year_start AND l.metric='期末余额',t.value,0)),2) year_opening
                FROM fin_report_line l
                LEFT JOIN (
                  SELECT l.id line_id,v.voucher_date,l.factor*CASE l.metric
                    WHEN '借方发生' THEN e.debit WHEN '贷方发生' THEN e.credit
                    ELSE IF(a.balance_side='借', e.debit-e.credit, e.credit-e.debit) END value
                  FROM fin_report_line l JOIN fin_account a ON a.id=l.account_id JOIN fin_voucher_line e ON e.account_id=a.id
                    JOIN fin_voucher v ON v.id=e.voucher_id AND v.status='已记账' WHERE l.report_id=:id
                ) t ON t.line_id=l.id
                WHERE l.report_id=:id GROUP BY l.line_no,l.label ORDER BY l.line_no
                """, values("id", report.get("id"), "start", period.atDay(1), "end", period.atEndOfMonth(),
                "year_start", period.atDay(1).withDayOfYear(1), "previous_start", previous.atDay(1), "previous_end", previous.atEndOfMonth()));
        return values("report", report, "period_code", period.toString(), "comparison_period", previous.toString(),
                "items", rows, "total", rows.size());
    }

    /**
     * 读取指定报表行的取数项和凭证分录贡献值。
     */
    public Map<String, Object> reportDetails(String type, long reportId, int lineNo, String periodCode) {
        var period = month(periodCode);
        require(lineNo > 0, "行次必须为正整数");
        var params = values("id", reportId, "type", type, "line", lineNo, "start", period.atDay(1), "end", period.atEndOfMonth());
        var terms = db.rows("""
                SELECT l.id,l.label,l.metric,l.factor,l.account_id,a.code,a.name
                FROM fin_report_line l JOIN fin_report r ON r.id=l.report_id
                JOIN fin_account a ON a.id=l.account_id
                WHERE r.id=:id AND r.report_type=:type AND r.status='已启用' AND l.line_no=:line ORDER BY l.id
                """, params);
        state(!terms.isEmpty(), "报表行次不存在或模板未启用");
        var rows = db.rows("""
                SELECT l.id term_id,a.code account_code,a.name account_name,v.id voucher_id,v.voucher_no,v.voucher_date,
                       e.id voucher_line_id,e.debit,e.credit,e.summary,
                       l.factor*CASE l.metric WHEN '借方发生' THEN e.debit WHEN '贷方发生' THEN e.credit
                         ELSE IF(a.balance_side='借',e.debit-e.credit,e.credit-e.debit) END contribution
                FROM fin_report_line l JOIN fin_account a ON a.id=l.account_id
                JOIN fin_voucher_line e ON e.account_id=a.id JOIN fin_voucher v ON v.id=e.voucher_id
                WHERE l.report_id=:id AND l.line_no=:line AND v.status='已记账'
                  AND v.voucher_date<=:end AND (l.metric='期末余额' OR v.voucher_date>=:start)
                ORDER BY v.voucher_date,v.id,e.line_no,l.id LIMIT 2001
                """, params);
        require(rows.size() <= 2000, "联查超过 2000 行，请按科目查询明细账");
        return values("terms", terms, "items", rows, "total", rows.size());
    }

    /**
     * 新建报表模板或在事务中整体替换现有模板的取数行。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long configureReport(String type, Long reportId, Map<String, Object> body, long actor) {
        var lines = items(body, "lines", 300);
        String name = requiredText(body, "name");
        long id;
        if (reportId == null) {
            id = db.insert("fin_report", values("name", name, "report_type", type, "owner_id", actor));
        } else {
            var old = db.row("fin_report", reportId, true);
            state(type.equals(old.get("report_type")), "报表类型不匹配");
            id = reportId;
            db.update("UPDATE fin_report SET name=:name,updated_at=CURRENT_TIMESTAMP WHERE id=:id", values("name", name, "id", id));
            db.update("DELETE FROM fin_report_line WHERE report_id=:id", values("id", id));
        }
        var labels = new HashMap<Long, String>();
        for (var line : lines) {
            long lineNo = id(line, "line_no");
            require(lineNo <= 100_000, "报表行次过大");
            var label = requiredText(line, "label");
            var previous = labels.putIfAbsent(lineNo, label);
            require(previous == null || previous.equals(label), "同一行次的项目名称须一致");
            db.insert("fin_report_line", values("report_id", id, "line_no", lineNo, "label", label,
                    "account_id", id(line, "account_id"), "metric", requiredText(line, "metric"),
                    "factor", line.get("factor") == null ? 1 : decimal(line, "factor")));
        }
        db.audit(actor, "reports/" + type, "配置报表公式", id, null);
        return id;
    }

    /**
     * 返回未记账凭证、借贷不平、未提折旧、待审核往来及未结转损益等阻塞项。
     */
    public Map<String, Object> closingCheck(long id) {
        var period = db.row("fin_period", id, false);
        var pending = db.rows("""
                SELECT id,voucher_no,status FROM fin_voucher WHERE period_id=:id AND status NOT IN ('已记账','已作废')
                ORDER BY serial_no
                """, values("id", id));
        var imbalance = db.rows("""
                SELECT id,voucher_no FROM fin_voucher WHERE period_id=:id AND status='已记账' AND debit_total<>credit_total
                """, values("id", id));
        var depreciation = db.rows("""
                SELECT a.id,a.code FROM fin_asset a WHERE a.status='使用中' AND a.acquisition_date<:start
                  AND a.original_value-a.accumulated_depreciation>ROUND(a.original_value*a.residual_rate,2)
                  AND NOT EXISTS (SELECT 1 FROM fin_depreciation d WHERE d.asset_id=a.id AND d.period_id=:id)
                """, values("id", id, "start", period.get("start_date")));
        var trade = db.rows("""
                SELECT id,order_no FROM fin_trade WHERE status='待审核' AND order_date BETWEEN :start AND :end
                """, values("start", period.get("start_date"), "end", period.get("end_date")));
        var pnl = db.rows("""
                SELECT a.id,a.code,SUM(l.debit-l.credit) balance FROM fin_account a
                JOIN fin_voucher_line l ON l.account_id=a.id JOIN fin_voucher v ON v.id=l.voucher_id
                WHERE a.category='损益' AND v.status='已记账' AND v.period_id=:id GROUP BY a.id,a.code
                HAVING SUM(l.debit-l.credit)<>0
                """, values("id", id));
        return values("ready", pending.isEmpty() && imbalance.isEmpty() && depreciation.isEmpty() && trade.isEmpty() && pnl.isEmpty(),
                "pending_vouchers", pending, "imbalanced_vouchers", imbalance, "missing_depreciation", depreciation,
                "pending_trade", trade, "profit_and_loss", pnl);
    }

    /**
     * 根据动作执行损益结转、月末结账或反结账，并校验后续期间及年度快照限制。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long close(long id, String action, Map<String, Object> body, long actor) {
        var period = db.row("fin_period", id, true);
        String code = period.get("period_code").toString();
        if (action.equals("reopen")) {
            state("已结账".equals(period.get("status")), "期间未结账");
            state(db.rows("SELECT id FROM fin_year_carryover WHERE source_period_id=:id", values("id", id)).isEmpty(),
                    "该期间已生成年度结转快照");
            state(db.rows("SELECT id FROM fin_period WHERE period_code>:code AND status='已结账'", values("code", code)).isEmpty(),
                    "请先反结账后续期间");
            db.patch("fin_period", id, values("status", "进行中", "closed_date", null));
            db.audit(actor, "gl/closing", "反结账", id, requiredText(body, "reason"));
            return null;
        }
        state("进行中".equals(period.get("status")), "期间已结账");
        var check = closingCheck(id);
        if (action.equals("close")) {
            state(Boolean.TRUE.equals(check.get("ready")), "存在结账阻塞项，请先执行检查");
            state(db.rows("SELECT id FROM fin_period WHERE period_code<:code AND status='进行中'", values("code", code)).isEmpty(),
                    "请先结账此前期间");
            db.update("UPDATE fin_period SET status='已结账',closed_date=CURRENT_DATE WHERE id=:id", values("id", id));
            db.audit(actor, "gl/closing", "月末结账", id, code);
            return null;
        }
        require(action.equals("transfer-profit"), "未知期末操作");
        state(((List<?>) check.get("pending_vouchers")).isEmpty(), "请先完成待记账凭证");
        var balances = db.rows("""
                SELECT a.id,SUM(l.debit-l.credit) balance,l.department_id,l.project_id,l.party_id
                FROM fin_account a JOIN fin_voucher_line l ON l.account_id=a.id JOIN fin_voucher v ON v.id=l.voucher_id
                WHERE a.category='损益' AND v.status='已记账' AND v.period_id=:id
                GROUP BY a.id,l.department_id,l.project_id,l.party_id HAVING SUM(l.debit-l.credit)<>0
                """, values("id", id));
        state(!balances.isEmpty(), "本期没有待结转损益");
        long target = id(body, "profit_account_id");
        require("权益".equals(db.row("fin_account", target, false).get("category")), "本年利润须使用权益科目");
        var lines = new ArrayList<Map<String, Object>>();
        var net = java.math.BigDecimal.ZERO;
        for (var balance : balances) {
            var amount = decimal(balance, "balance");
            net = net.add(amount);
            lines.add(CashService.line(id(balance, "id"), amount.abs(), amount.signum() < 0, balance));
        }
        if (net.signum() != 0) {
            lines.add(CashService.line(target, net.abs(), net.signum() > 0, Map.of()));
        }
        return ledger.create(period, date(period, "end_date"), "期末损益结转 " + code, lines, actor, null, true);
    }

    /**
     * 将 yyyy-MM 格式的参数解析为会计月份。
     */
    static YearMonth month(String code) {
        try {
            return YearMonth.parse(Objects.toString(code, ""));
        } catch (java.time.DateTimeException ex) {
            require(false, "会计期间格式应为 yyyy-MM");
            throw ex;
        }
    }
}
