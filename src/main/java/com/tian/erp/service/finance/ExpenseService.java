package com.tian.erp.service.finance;

import com.tian.erp.common.exception.BusinessException;
import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.service.finance.CashService.*;
import static com.tian.erp.support.finance.FinanceFields.*;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * 管理报销明细、版本化审批任务、借款关联及报销支付。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ExpenseService {
    private final FinanceRepository db;
    private final FinanceSystemService system;
    private final LedgerService ledger;
    private final CashService cash;

    /**
     * 注入报销数据访问、基础数据、总账和资金服务。
     */
    ExpenseService(FinanceRepository db, FinanceSystemService system, LedgerService ledger, CashService cash) {
        this.db = db;
        this.system = system;
        this.ledger = ledger;
        this.cash = cash;
    }

    /**
     * 校验申请部门、费用类型限额及借款冲抵，保存草稿和费用明细。
     */
    long claim(Map<String, Object> body, long actor) {
        var user = db.row("fin_user", actor, false);
        long department = id(body, "department_id");
        system.master(department, "部门");
        require(AuthService.admin(user) || user.get("department_id") != null && id(user, "department_id") == department,
                "请选择本人部门");
        long expenseType = id(body, "expense_type_id");
        system.master(expenseType, "费用类型");
        var lines = items(body, "lines", 100);
        var totals = new HashMap<Long, BigDecimal>();
        var detail = new ArrayList<Map<String, Object>>();
        var total = BigDecimal.ZERO;
        for (var line : lines) {
            long type = id(line, "expense_type_id");
            var master = system.master(type, "费用类型");
            var amount = positive(line, "amount");
            var typeTotal = totals.merge(type, amount, BigDecimal::add);
            require(master.get("amount_limit") == null || typeTotal.compareTo(decimal(master, "amount_limit")) <= 0,
                    "费用类型累计金额超限: " + master.get("name"));
            var fields = fields(line, "expense_type_id account_id project_id");
            fields.putAll(values("department_id", department, "expense_date", date(line, "expense_date"),
                    "amount", amount, "purpose", requiredText(line, "purpose")));
            db.row("fin_account", id(line, "account_id"), false);
            detail.add(fields);
            total = total.add(amount);
        }
        require(totals.containsKey(expenseType), "主要费用类型须包含在明细中");
        var offset = body.get("loan_offset") == null ? BigDecimal.ZERO : money(body, "loan_offset");
        require(offset.signum() >= 0 && offset.compareTo(total) <= 0, "无效借款冲抵金额");
        Long loanId = body.get("loan_id") == null ? null : id(body, "loan_id");
        if (loanId != null || offset.signum() > 0) {
            require(loanId != null && offset.signum() > 0, "请同时填写借款与冲抵金额");
            validateLoan(db.row("fin_loan", loanId, false), actor, offset);
        }
        long id = db.insert("fin_claim", values("claim_no", orderNo(body, "claim_no", "BX"), "applicant_id", actor,
                "department_id", department, "expense_type_id", expenseType, "amount", total,
                "loan_offset", offset, "loan_id", loanId));
        for (var line : detail) {
            line.put("claim_id", id);
            db.insert("fin_claim_line", line);
        }
        db.audit(actor, "expense/claims", "新建报销", id, null);
        return id;
    }

    /**
     * 为申请人的草稿匹配已发布流程，建立首节点任务并转为待审批。
     */
    void submit(long id, long actor) {
        var claim = db.row("fin_claim", id, true);
        state(id(claim, "applicant_id") == actor && "草稿".equals(claim.get("approval_status")), "仅申请人可提交草稿");
        if (claim.get("loan_id") != null) {
            validateLoan(db.row("fin_loan", id(claim, "loan_id"), false), actor, decimal(claim, "loan_offset"));
        }
        var candidates = db.rows("""
                SELECT w.* FROM fin_workflow w WHERE w.status='已启用'
                  AND (w.department_id IS NULL OR w.department_id=:dept)
                  AND (w.expense_type_id IS NULL OR w.expense_type_id=:type)
                  AND w.min_amount<=:amount AND (w.max_amount IS NULL OR w.max_amount>=:amount)
                  AND w.version=(SELECT MAX(v.version) FROM fin_workflow v WHERE v.name=w.name AND v.status='已启用')
                ORDER BY (w.department_id IS NOT NULL)+(w.expense_type_id IS NOT NULL) DESC,w.id LIMIT 2
                """, values("dept", claim.get("department_id"), "type", claim.get("expense_type_id"), "amount", claim.get("amount")));
        state(!candidates.isEmpty(), "没有匹配的已发布审批流程");
        var workflow = candidates.get(0);
        if (candidates.size() == 2) {
            state(specificity(workflow) > specificity(candidates.get(1)), "存在多个同优先级审批流程");
        }
        state(enqueue(id, id(workflow, "id"), 0, actor), "审批流程没有节点");
        db.patch("fin_claim", id, values("workflow_id", workflow.get("id"), "approval_status", "待审批"));
        db.audit(actor, "expense/claims", "提交报销审批", id, null);
    }

    /**
     * 计算流程的部门及费用类型限定数，用于匹配优先级比较。
     */
    private int specificity(Map<String, Object> workflow) {
        return (workflow.get("department_id") == null ? 0 : 1) + (workflow.get("expense_type_id") == null ? 0 : 1);
    }

    /**
     * 查找下一节点并按串行、或签或会签规则建立有效审批人的任务。
     */
    private boolean enqueue(long claim, long workflow, int after, long applicant) {
        var steps = db.rows("""
                SELECT * FROM fin_workflow_step WHERE workflow_id=:workflow AND step_no>:after ORDER BY step_no LIMIT 1
                """, values("workflow", workflow, "after", after));
        if (steps.isEmpty()) {
            return false;
        }
        var step = steps.get(0);
        var users = db.rows("""
                SELECT u.id FROM fin_user u JOIN fin_role r ON r.id=u.role_id
                WHERE u.role_id=:role AND u.id<>:applicant AND u.status='已启用' AND r.status='已启用'
                  AND (u.is_admin=TRUE OR EXISTS (
                     SELECT 1 FROM fin_permission p WHERE p.role_id=u.role_id AND p.resource='expense/approvals' AND p.can_write=TRUE))
                ORDER BY u.id
                """, values("role", step.get("role_id"), "applicant", applicant));
        state(!users.isEmpty(), "节点没有可用审批人: " + step.get("name"));
        if ("串行".equals(step.get("mode"))) {
            users = users.subList(0, 1);
        }
        for (var user : users) {
            db.insert("fin_approval_task", values("claim_id", claim, "step_id", step.get("id"), "assignee_id", user.get("id")));
        }
        return true;
    }

    /**
     * 由受派人同意、驳回、转交或加签当前任务，记录历史并按节点条件推进流程。
     */
    void handle(long taskId, String action, Map<String, Object> body, long actor) {
        var snapshot = db.row("fin_approval_task", taskId, false);
        long claimId = id(snapshot, "claim_id");
        var claim = db.row("fin_claim", claimId, true);
        var task = db.row("fin_approval_task", taskId, true);
        if (id(task, "assignee_id") != actor) {
            throw new ResponseStatusException(FORBIDDEN, "仅当前审批人可办理");
        }
        state("待审批".equals(task.get("status")) && "待审批".equals(claim.get("approval_status")), "审批任务已完成");
        long stepId = id(task, "step_id");
        var step = db.row("fin_workflow_step", stepId, false);
        long applicant = id(claim, "applicant_id");
        var scope = values("claim", claimId, "step", stepId);
        switch (action) {
            case "approve" -> {
                finish(taskId, "已通过", Objects.toString(body.get("opinion"), ""));
                if ("或签".equals(step.get("mode")) && !db.rows("""
                        SELECT id FROM fin_approval_task WHERE claim_id=:claim AND step_id=:step
                          AND status='已通过' AND required_sign=FALSE
                        """, scope).isEmpty() && db.rows("""
                        SELECT id FROM fin_approval_task WHERE claim_id=:claim AND step_id=:step
                          AND status='待审批' AND required_sign=TRUE
                        """, scope).isEmpty()) {
                    cancel(scope);
                }
                if (db.rows("""
                        SELECT id FROM fin_approval_task WHERE claim_id=:claim AND step_id=:step AND status='待审批'
                        """, scope).isEmpty() && !enqueue(claimId, id(claim, "workflow_id"),
                        ((Number) step.get("step_no")).intValue(), applicant)) {
                    db.patch("fin_claim", claimId, values("approval_status", "已通过"));
                }
            }
            case "reject" -> {
                finish(taskId, "已驳回", requiredText(body, "opinion"));
                cancel(scope);
                db.patch("fin_claim", claimId, values("approval_status", "已驳回"));
            }
            case "transfer", "add-signer" -> {
                long assignee = id(body, "assignee_id");
                state(assignee != actor && assignee != applicant, "审批人不能是本人或申请人");
                var user = db.row("fin_user", assignee, false);
                state("已启用".equals(user.get("status")) && Objects.equals(user.get("role_id"), step.get("role_id"))
                        && (AuthService.admin(user) || !db.rows("""
                           SELECT role_id FROM fin_permission WHERE role_id=:role AND resource='expense/approvals' AND can_write=TRUE
                           """, values("role", user.get("role_id"))).isEmpty()), "目标用户无该节点审批资格");
                var assigned = db.rows("""
                        SELECT id,status,required_sign FROM fin_approval_task
                        WHERE claim_id=:claim AND step_id=:step AND assignee_id=:user
                        """, values("claim", claimId, "step", stepId, "user", assignee));
                if (action.equals("add-signer") && !assigned.isEmpty()) {
                    var existing = assigned.get(0);
                    state("待审批".equals(existing.get("status")), "目标用户已办理该节点");
                    db.patch("fin_approval_task", id(existing, "id"), values("required_sign", true));
                    break;
                }
                state(assigned.isEmpty(), "目标用户已参与该节点");
                if (action.equals("transfer")) {
                    finish(taskId, "已转交", Objects.toString(body.get("opinion"), ""));
                }
                boolean required = action.equals("add-signer") || Boolean.TRUE.equals(task.get("required_sign"));
                db.insert("fin_approval_task", values("claim_id", claimId, "step_id", stepId,
                        "assignee_id", assignee, "required_sign", required));
            }
            default -> throw BusinessException.unsupported();
        }
        db.audit(actor, "expense/approvals", action, taskId, "报销单 " + claimId);
    }

    /**
     * 写入任务的办理状态、意见与处理时间。
     */
    private void finish(long task, String status, String opinion) {
        db.update("UPDATE fin_approval_task SET status=:status,opinion=:opinion,handled_at=CURRENT_TIMESTAMP WHERE id=:id",
                values("id", task, "status", status, "opinion", opinion));
    }

    /**
     * 将指定报销单和节点下尚未办理的任务标记为已取消。
     */
    private void cancel(Map<String, Object> scope) {
        db.update("""
                UPDATE fin_approval_task SET status='已取消',handled_at=CURRENT_TIMESTAMP
                WHERE claim_id=:claim AND step_id=:step AND status='待审批'
                """, scope);
    }

    /**
     * 以申请人身份登记借款申请、预计还款日和借款核算科目。
     */
    long loan(Map<String, Object> body, long actor) {
        var date = date(body, "due_date");
        var amount = positive(body, "amount");
        long account = id(body, "loan_account_id");
        db.row("fin_account", account, false);
        long id = db.insert("fin_loan", values("loan_no", orderNo(body, "loan_no", "JK"), "applicant_id", actor,
                "purpose", requiredText(body, "purpose"), "amount", amount, "remaining_amount", amount, "due_date", date,
                "loan_account_id", account));
        db.audit(actor, "expense/loans", "申请借款", id, null);
        return id;
    }

    /**
     * 由其他人员将借款审核或还款登记关联到已确认的资金单。
     */
    void loanAction(long id, String action, Map<String, Object> body, long actor) {
        var loan = db.row("fin_loan", id, true);
        state(id(loan, "applicant_id") != actor, "借款人不可确认本人借款或还款");
        long orderId = id(body, "cash_order_id");
        var order = db.row("fin_cash_order", orderId, true);
        var bank = db.row("fin_bank_account", id(order, "bank_account_id"), false);
        state("GENERAL".equals(order.get("business_type")) && "已确认".equals(order.get("status"))
                && "CNY".equals(bank.get("currency")) && Objects.equals(loan.get("loan_account_id"), order.get("counterpart_account_id")),
                "资金单必须已确认，使用本位币及对应借款科目");
        if (action.equals("approve")) {
            state("待审批".equals(loan.get("status")) && "付款".equals(order.get("direction"))
                    && decimal(loan, "amount").compareTo(decimal(order, "amount")) == 0, "借款状态或付款金额不匹配");
            db.patch("fin_loan", id, values("status", "已通过", "cash_order_id", orderId));
        } else if (action.equals("repay")) {
            var amount = decimal(order, "amount");
            state("收款".equals(order.get("direction")), "还款须关联已确认的收款单");
            validateLoan(loan, id(loan, "applicant_id"), amount);
            db.insert("fin_loan_repayment", values("loan_id", id, "cash_order_id", orderId, "amount", amount));
            reduceLoan(loan, amount);
        } else {
            throw BusinessException.unsupported();
        }
        db.audit(actor, "expense/loans", action, id, null);
    }

    /**
     * 锁定已通过的未支付报销单，生成凭证并更新资金、借款冲抵和支付状态。
     */
    long pay(long claimId, Map<String, Object> body, long actor) {
        var claim = db.row("fin_claim", claimId, true);
        state("已通过".equals(claim.get("approval_status")) && "待支付".equals(claim.get("payment_status"))
                && id(claim, "applicant_id") != actor, "须由其他人员支付已通过且未付款的报销单");
        var date = date(body, "transaction_date");
        var offset = decimal(claim, "loan_offset");
        var net = decimal(claim, "amount").subtract(offset);
        Map<String, Object> bank = null;
        if (net.signum() > 0) {
            bank = db.row("fin_bank_account", id(body, "bank_account_id"), true);
            state("CNY".equals(bank.get("currency")) && decimal(bank, "balance").compareTo(net) >= 0, "资金余额不足或账户币种非本位币");
        }
        Map<String, Object> loan = null;
        if (offset.signum() > 0) {
            loan = db.row("fin_loan", id(claim, "loan_id"), true);
            validateLoan(loan, id(claim, "applicant_id"), offset);
        }
        var period = ledger.period(date);
        if (bank != null) {
            cash.unlocked(bank, period);
        }
        var lines = new ArrayList<Map<String, Object>>();
        var expenses = db.rows("SELECT * FROM fin_claim_line WHERE claim_id=:id ORDER BY id", values("id", claimId));
        for (var row : expenses) {
            require(!date.isBefore(date(row, "expense_date")), "报销日期不得早于费用日期");
            lines.add(line(id(row, "account_id"), decimal(row, "amount"), true, row));
        }
        if (loan != null) {
            lines.add(line(id(loan, "loan_account_id"), offset, false, claim));
        }
        if (bank != null) {
            lines.add(line(id(bank, "ledger_account_id"), net, false, claim));
        }
        long voucher = ledger.create(period, date, "报销支付 " + claim.get("claim_no"), lines, actor, null, true);
        Long order = null;
        if (bank != null) {
            order = db.insert("fin_cash_order", values("order_no", orderNo(body, "order_no", "BXFK"), "direction", "付款",
                    "business_type", "EXPENSE", "bank_account_id", bank.get("id"), "counterpart_account_id", expenses.get(0).get("account_id"),
                    "transaction_date", date, "amount", net, "purpose", "报销 " + claim.get("claim_no"), "maker_id", actor,
                    "voucher_id", voucher, "status", "已确认"));
            db.patch("fin_bank_account", id(bank, "id"), values("balance", decimal(bank, "balance").subtract(net)));
        }
        if (loan != null) {
            reduceLoan(loan, offset);
        }
        db.patch("fin_claim", claimId, values("payment_status", "已支付", "cash_order_id", order, "voucher_id", voucher));
        db.audit(actor, "expense/expense-payments", "执行付款", claimId, "凭证 " + voucher);
        return voucher;
    }

    /**
     * 验证借款申请人、审批状态和可用借款余额。
     */
    private void validateLoan(Map<String, Object> loan, long applicant, BigDecimal amount) {
        state(id(loan, "applicant_id") == applicant && "已通过".equals(loan.get("status"))
                && amount.compareTo(decimal(loan, "remaining_amount")) <= 0, "借款单不匹配或可用余额不足");
    }

    /**
     * 扣减借款余额，并在余额为零时将借款标记为已结清。
     */
    private void reduceLoan(Map<String, Object> loan, BigDecimal amount) {
        var remaining = decimal(loan, "remaining_amount").subtract(amount);
        db.patch("fin_loan", id(loan, "id"), values("remaining_amount", remaining,
                "status", remaining.signum() == 0 ? "已结清" : "已通过"));
    }
}
