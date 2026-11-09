package com.tian.erp.service.finance;

import com.tian.erp.common.response.PageResult;
import com.tian.erp.repository.finance.FinanceRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.support.finance.FinanceFields.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 用预定义 SQL 和筛选白名单读取财务页面记录，并应用支持的个人数据范围。
 */
@Service
@Transactional(readOnly = true)
public class FinanceQueryService {
    private static final Map<String, Page> PAGES = Map.ofEntries(
            Map.entry("gl/accounts", page("""
                    SELECT a.*, p.code parent_code, NOT EXISTS (SELECT 1 FROM fin_account c WHERE c.parent_id=a.id) leaf
                    FROM fin_account a LEFT JOIN fin_account p ON p.id=a.parent_id
                    """, "code name category status parent_id account_level")),
            Map.entry("gl/vouchers", page("""
                    SELECT v.*,p.period_code,m.name maker,r.name reviewer FROM fin_voucher v
                    JOIN fin_period p ON p.id=v.period_id JOIN fin_user m ON m.id=v.maker_id LEFT JOIN fin_user r ON r.id=v.reviewer_id
                    """, "period_code voucher_no summary status voucher_date")),
            Map.entry("gl/closing", page("""
                    SELECT p.*, (SELECT COUNT(*) FROM fin_voucher v WHERE v.period_id=p.id AND v.status NOT IN ('已记账','已作废')) pending_vouchers
                    FROM fin_period p
                    """, "period_code status")),
            Map.entry("ar/receivables", page("""
                    SELECT t.*,m.name party,t.amount-t.settled_amount open_amount FROM fin_trade t JOIN fin_master m ON m.id=t.party_id WHERE t.side='AR'
                    """, "party_id party due_date status currency")),
            Map.entry("ap/payables", page("""
                    SELECT t.*,m.name party,t.amount-t.settled_amount open_amount FROM fin_trade t JOIN fin_master m ON m.id=t.party_id WHERE t.side='AP'
                    """, "party_id party due_date status currency")),
            Map.entry("ar/collections", page("""
                    SELECT c.*, b.name account_name,b.currency,m.name party,
                    COALESCE(s.used_amount,0) settled_amount,c.amount-COALESCE(s.used_amount,0) available_amount
                    FROM fin_cash_order c JOIN fin_bank_account b ON b.id=c.bank_account_id JOIN fin_master m ON m.id=c.party_id
                    LEFT JOIN (SELECT cash_order_id,SUM(amount) used_amount FROM fin_settlement WHERE reversed_on IS NULL GROUP BY cash_order_id) s ON s.cash_order_id=c.id
                    WHERE c.direction='收款' AND c.business_type='AR'
                    """, "party_id party transaction_date status account_name")),
            Map.entry("ap/payments", page("""
                    SELECT c.*, b.name account_name,b.currency,m.name party,
                    COALESCE(s.used_amount,0) settled_amount,c.amount-COALESCE(s.used_amount,0) available_amount
                    FROM fin_cash_order c JOIN fin_bank_account b ON b.id=c.bank_account_id JOIN fin_master m ON m.id=c.party_id
                    LEFT JOIN (SELECT cash_order_id,SUM(amount) used_amount FROM fin_settlement WHERE reversed_on IS NULL GROUP BY cash_order_id) s ON s.cash_order_id=c.id
                    WHERE c.direction='付款' AND c.business_type='AP'
                    """, "party_id party transaction_date status account_name")),
            Map.entry("ar/bad-debts", page("""
                    SELECT d.*,t.party_id,m.name party,p.period_code FROM fin_bad_debt d JOIN fin_trade t ON t.id=d.trade_id
                    JOIN fin_master m ON m.id=t.party_id JOIN fin_period p ON p.id=d.period_id
                    """, "period_code party party_id status treatment")),
            Map.entry("ap/notes", page("""
                    SELECT n.*,m.name party FROM fin_note n JOIN fin_master m ON m.id=n.party_id
                    """, "party party_id note_type due_date status")),
            Map.entry("funds/bank-accounts", page("""
                    SELECT id,name,bank_name,CASE WHEN account_number IS NULL THEN NULL ELSE CONCAT('•••• ',RIGHT(account_number,4)) END account_number,
                    currency,account_type,ledger_account_id,opening_balance,balance,status FROM fin_bank_account
                    """, "name currency account_type status")),
            Map.entry("funds/cash-orders", page("""
                    SELECT c.*,b.name account_name,b.currency,m.name party FROM fin_cash_order c
                    JOIN fin_bank_account b ON b.id=c.bank_account_id LEFT JOIN fin_master m ON m.id=c.party_id
                    """, "account_name bank_account_id transaction_date direction status currency")),
            Map.entry("funds/reconciliation", page("""
                    SELECT s.*,b.name account_name,p.period_code,c.amount book_amount,
                    CASE WHEN c.id IS NULL THEN '未匹配' ELSE '已匹配' END match_status,(l.period_id IS NOT NULL) locked
                    FROM fin_bank_statement s JOIN fin_bank_account b ON b.id=s.bank_account_id
                    LEFT JOIN fin_cash_order c ON c.id=s.matched_order_id
                    LEFT JOIN fin_period p ON s.transaction_date BETWEEN p.start_date AND p.end_date
                    LEFT JOIN fin_reconciliation_lock l ON l.period_id=p.id AND l.bank_account_id=s.bank_account_id
                    """, "account_name bank_account_id period_code match_status transaction_date")),
            Map.entry("funds/cash-plans", page("""
                    SELECT q.*,actual_receipts-actual_payments-planned_receipts+planned_payments variance FROM (
                    SELECT p.*,b.name account_name,b.currency,DATE_FORMAT(p.start_date,'%Y-%m') period_code,
                    COALESCE(SUM(CASE WHEN c.direction='收款' THEN c.amount END),0) actual_receipts,
                    COALESCE(SUM(CASE WHEN c.direction='付款' THEN c.amount END),0) actual_payments
                    FROM fin_cash_plan p JOIN fin_bank_account b ON b.id=p.bank_account_id LEFT JOIN fin_cash_order c
                    ON c.bank_account_id=p.bank_account_id AND c.transaction_date BETWEEN p.start_date AND p.end_date AND c.status='已确认'
                    GROUP BY p.id,p.bank_account_id,p.cycle,p.start_date,p.end_date,p.planned_receipts,p.planned_payments,b.name,b.currency
                    ) q
                    """, "period_code cycle account_name bank_account_id")),
            Map.entry("expense/claims", page("""
                    SELECT c.*,u.name applicant,d.name department,e.name expense_type,c.amount-c.loan_offset payable_amount
                    FROM fin_claim c JOIN fin_user u ON u.id=c.applicant_id JOIN fin_master d ON d.id=c.department_id JOIN fin_master e ON e.id=c.expense_type_id
                    """, "applicant applicant_id department department_id expense_type approval_status payment_status")),
            Map.entry("expense/expense-payments", page("""
                    SELECT c.*,u.name applicant,d.name department,e.name expense_type,c.amount-c.loan_offset payable_amount,
                    COALESCE(o.amount,0) paid_amount FROM fin_claim c JOIN fin_user u ON u.id=c.applicant_id
                    JOIN fin_master d ON d.id=c.department_id JOIN fin_master e ON e.id=c.expense_type_id
                    LEFT JOIN fin_cash_order o ON o.id=c.cash_order_id WHERE c.approval_status='已通过'
                    """, "applicant applicant_id department department_id payment_status")),
            Map.entry("expense/loans", page("""
                    SELECT l.*,u.name applicant,(l.remaining_amount>0 AND l.due_date<CURRENT_DATE AND l.status='已通过') overdue
                    FROM fin_loan l JOIN fin_user u ON u.id=l.applicant_id
                    """, "applicant applicant_id due_date status")),
            Map.entry("expense/approvals", page("""
                    SELECT t.*,s.name current_node,c.claim_no,c.amount,c.applicant_id,u.name applicant,e.name expense_type
                    FROM fin_approval_task t JOIN fin_workflow_step s ON s.id=t.step_id JOIN fin_claim c ON c.id=t.claim_id
                    JOIN fin_user u ON u.id=c.applicant_id JOIN fin_master e ON e.id=c.expense_type_id
                    """, "applicant expense_type status claim_id")),
            Map.entry("fa/asset-cards", page("""
                    SELECT a.*,m.name department,a.original_value-a.accumulated_depreciation net_value
                    FROM fin_asset a JOIN fin_master m ON m.id=a.department_id
                    """, "code name category department department_id status")),
            Map.entry("fa/depreciation", page("""
                    SELECT d.*,a.code asset_code,a.name asset_name,a.category,a.department_id,a.method,p.period_code,m.name department,d.amount+d.adjustment current_amount,
                    '已计提' status FROM fin_depreciation d JOIN fin_asset a ON a.id=d.asset_id
                    JOIN fin_period p ON p.id=d.period_id JOIN fin_master m ON m.id=a.department_id
                    """, "period_code category department department_id asset_code")),
            Map.entry("fa/asset-movements", page("""
                    SELECT m.*,a.code asset_code,d.name old_department,n.name new_department FROM fin_asset_movement m
                    JOIN fin_asset a ON a.id=m.asset_id JOIN fin_master d ON d.id=m.old_department_id LEFT JOIN fin_master n ON n.id=m.new_department_id
                    """, "asset_code movement_type status")),
            Map.entry("fa/asset-disposals", page("""
                    SELECT d.*,a.code asset_code,d.proceeds-d.net_value gain FROM fin_asset_disposal d JOIN fin_asset a ON a.id=d.asset_id
                    """, "asset_code disposal_type status")),
            Map.entry("reports/custom-reports", page("""
                    SELECT r.*,u.name owner FROM fin_report r JOIN fin_user u ON u.id=r.owner_id WHERE r.report_type='custom-reports'
                    """, "name status")),
            Map.entry("system/users", page("""
                    SELECT u.id,u.username,u.name,u.department_id,d.name department,u.role_id,r.name role,u.status,u.is_admin
                    FROM fin_user u JOIN fin_role r ON r.id=u.role_id LEFT JOIN fin_master d ON d.id=u.department_id
                    """, "username name department department_id status")),
            Map.entry("system/permissions", page("""
                    SELECT r.*,COALESCE((SELECT GROUP_CONCAT(p.resource ORDER BY p.resource SEPARATOR ',') FROM fin_permission p WHERE p.role_id=r.id),'') permissions
                    FROM fin_role r
                    """, "name status")),
            Map.entry("system/workflow", page("""
                    SELECT w.*,d.name department,e.name expense_type FROM fin_workflow w
                    LEFT JOIN fin_master d ON d.id=w.department_id LEFT JOIN fin_master e ON e.id=w.expense_type_id
                    """, "name department department_id status")),
            Map.entry("system/master-data", page("""
                    SELECT * FROM fin_master
                    """, "data_type code name status")),
            Map.entry("system/periods", page("""
                    SELECT p.*,YEAR(p.start_date) fiscal_year FROM fin_period p
                    """, "period_code fiscal_year status")),
            Map.entry("system/logs", page("""
                    SELECT a.*,u.name operator FROM fin_audit a JOIN fin_user u ON u.id=a.operator_id
                    """, "operator operation resource result operated_at")),
            Map.entry("system/backups", page("""
                    SELECT * FROM fin_backup_record
                    """, "status backup_at"))
    );

    private static final Map<String, List<String>> SEARCH = Map.of(
            "gl/accounts", List.of("code", "name"), "gl/vouchers", List.of("voucher_no", "summary"),
            "fa/asset-cards", List.of("code", "name"), "system/users", List.of("username", "name"),
            "system/master-data", List.of("code", "name"));

    private static final Set<String> DERIVED = Set.of("gl/ledgers", "ar/ar-aging", "ap/ap-aging",
            "reports/balance-sheet", "reports/income-statement", "reports/cash-flow", "dashboard");

    /**
     * 判断资源是否为常规页面、分析页面或工作台。
     */
    static boolean known(String resource) {
        return PAGES.containsKey(resource) || DERIVED.contains(resource);
    }

    /**
     * 返回排序后的完整财务资源标识集合。
     */
    public static Set<String> resources() {
        var resources = new TreeSet<>(PAGES.keySet());
        resources.addAll(DERIVED);
        return resources;
    }

    private final FinanceRepository db;

    /**
     * 注入财务查询的数据访问组件。
     */
    FinanceQueryService(FinanceRepository db) {
        this.db = db;
    }

    /**
     * 保存常规查询 SQL 和允许外部使用的筛选字段集合。
     */
    private record Page(String sql, Set<String> filters) {}

    /**
     * 用查询 SQL 和允许的筛选字段构造页面定义。
     */
    private static Page page(String sql, String fields) {
        return new Page(sql, Set.of(fields.split(" ")));
    }

    /**
     * 读取常规页面的 SQL 定义，未知资源返回 HTTP 404。
     */
    private Page definition(String resource) {
        Page page = PAGES.get(resource);
        if (page == null) {
            throw new ResponseStatusException(NOT_FOUND, "未知业务页面");
        }
        return page;
    }

    /**
     * 校验筛选和分页参数，应用数据范围后读取总数与当前页。
     */
    public PageResult<Map<String, Object>> list(String resource, Map<String, String> filters, int page, int size, Map<String, Object> actor) {
        require(page >= 1 && size >= 1 && size <= 200, "分页大小应在 1–200 之间");
        Page definition = definition(resource);
        var params = new LinkedHashMap<String, Object>();
        var conditions = new ArrayList<String>();
        filters.forEach((key, value) -> {
            if (value.isBlank() || Set.of("page", "size").contains(key)) {
                return;
            }
            if (key.equals("q") && SEARCH.containsKey(resource)) {
                var clauses = SEARCH.get(resource).stream()
                        .map(column -> "LOCATE(:q, " + column + ")>0").toList();
                conditions.add("(" + String.join(" OR ", clauses) + ")");
                params.put("q", value);
            } else if (definition.filters().contains(key)) {
                conditions.add(key + "=:" + key);
                params.put(key, value);
            } else if (key.endsWith("_from") || key.endsWith("_to")) {
                String column = key.substring(0, key.lastIndexOf('_'));
                require(definition.filters().contains(column) && (column.endsWith("_date") || column.endsWith("_at")),
                        "未知筛选条件: " + key);
                date(values(key, value), key);
                String comparison = key.endsWith("_from") ? ">=" : "<";
                conditions.add(column + comparison + (comparison.equals("<") ? "DATE_ADD(:" + key + ", INTERVAL 1 DAY)" : ":" + key));
                params.put(key, value);
            } else {
                require(false, "未知筛选条件: " + key);
            }
        });
        scope(resource, actor, params, conditions);
        String from = " FROM (" + definition.sql() + ") page_data";
        if (!conditions.isEmpty()) {
            from += " WHERE " + String.join(" AND ", conditions);
        }
        long total = ((Number) db.one("SELECT COUNT(*) total" + from, params).get("total")).longValue();
        params.put("limit", size);
        params.put("offset", (long) (page - 1) * size);
        return new PageResult<>(db.rows("SELECT *" + from + " ORDER BY id DESC LIMIT :limit OFFSET :offset", params), total, page, size);
    }

    /**
     * 为普通用户的报销、借款或审批查询加入本人或受派人条件。
     */
    private void scope(String resource, Map<String, Object> actor, Map<String, Object> params, List<String> conditions) {
        if (AuthService.admin(actor)) {
            return;
        }
        switch (resource) {
            case "expense/claims", "expense/loans" -> conditions.add("applicant_id=:scope_actor");
            case "expense/approvals" -> conditions.add("assignee_id=:scope_actor");
            default -> { return; }
        }
        params.put("scope_actor", actor.get("id"));
    }

    /**
     * 读取可见的单条记录，并按资源附加分录、附件、审批历史等关联数据。
     */
    public Map<String, Object> detail(String resource, long id, Map<String, Object> actor) {
        var params = values("id", id);
        var conditions = new ArrayList<>(List.of("id=:id"));
        scope(resource, actor, params, conditions);
        var result = new LinkedHashMap<>(db.one("SELECT * FROM (" + definition(resource).sql() + ") page_data WHERE "
                + String.join(" AND ", conditions), params));
        switch (resource) {
            case "gl/vouchers" -> {
                result.put("lines", db.rows("""
                        SELECT l.*,a.code account_code,a.name account_name FROM fin_voucher_line l
                        JOIN fin_account a ON a.id=l.account_id WHERE l.voucher_id=:id ORDER BY l.line_no
                        """, params));
                result.put("attachments", db.rows("SELECT id,file_name,content_type FROM fin_attachment WHERE voucher_id=:id ORDER BY id", params));
            }
            case "ar/collections", "ap/payments" -> result.put("settlements",
                    db.rows("SELECT * FROM fin_settlement WHERE cash_order_id=:id ORDER BY id", params));
            case "ar/receivables", "ap/payables" -> result.put("settlements",
                    db.rows("SELECT * FROM fin_settlement WHERE trade_id=:id ORDER BY id", params));
            case "expense/claims" -> {
                result.put("lines", db.rows("SELECT * FROM fin_claim_line WHERE claim_id=:id ORDER BY id", params));
                result.put("tasks", db.rows("SELECT * FROM fin_approval_task WHERE claim_id=:id ORDER BY id", params));
                result.put("attachments", db.rows("SELECT id,file_name,content_type FROM fin_attachment WHERE claim_id=:id ORDER BY id", params));
            }
            case "expense/approvals" -> {
                var claim = values("id", result.get("claim_id"));
                result.put("lines", db.rows("SELECT * FROM fin_claim_line WHERE claim_id=:id ORDER BY id", claim));
                result.put("history", db.rows("""
                        SELECT t.id,t.assignee_id,u.name assignee,s.name step,t.status,t.opinion,t.handled_at
                        FROM fin_approval_task t JOIN fin_user u ON u.id=t.assignee_id
                        JOIN fin_workflow_step s ON s.id=t.step_id WHERE t.claim_id=:id ORDER BY s.step_no,t.id
                        """, claim));
                result.put("attachments", db.rows("SELECT id,file_name,content_type FROM fin_attachment WHERE claim_id=:id ORDER BY id", claim));
            }
            case "fa/depreciation" -> result.put("adjustments",
                    db.rows("SELECT * FROM fin_depreciation_adjustment WHERE depreciation_id=:id ORDER BY id", params));
            case "expense/loans" -> result.put("repayments", db.rows("SELECT * FROM fin_loan_repayment WHERE loan_id=:id ORDER BY id", params));
            case "fa/asset-cards" -> {
                result.put("depreciation", db.rows("SELECT * FROM fin_depreciation WHERE asset_id=:id ORDER BY period_id", params));
                result.put("movements", db.rows("SELECT * FROM fin_asset_movement WHERE asset_id=:id ORDER BY id", params));
                result.put("disposals", db.rows("SELECT * FROM fin_asset_disposal WHERE asset_id=:id ORDER BY id", params));
            }
            case "system/permissions" -> result.put("permissions", db.rows("SELECT resource,can_write FROM fin_permission WHERE role_id=:id", params));
            case "system/workflow" -> result.put("steps", db.rows("SELECT * FROM fin_workflow_step WHERE workflow_id=:id ORDER BY step_no", params));
            case "reports/custom-reports" -> result.put("lines", db.rows("SELECT * FROM fin_report_line WHERE report_id=:id ORDER BY line_no,id", params));
            default -> { }
        }
        return result;
    }
}
