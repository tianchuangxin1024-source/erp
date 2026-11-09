package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 管理财务用户、角色授权、基础数据与审批流程版本。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FinanceSystemService {
    private static final Set<String> MASTER_TYPES = Set.of("币种", "汇率", "部门", "项目", "客户", "供应商", "费用类型");
    private final FinanceRepository db;

    /**
     * 注入系统数据访问与审计组件。
     */
    FinanceSystemService(FinanceRepository db) {
        this.db = db;
    }

    /**
     * 新增或维护指定类型的基础数据，校验汇率、费用限额和启停状态。
     */
    public long saveMaster(Long id, Map<String, Object> body, long actor) {
        var fields = fields(body, "code name extra_info rate effective_date amount_limit status");
        String type;
        if (id == null) {
            type = requiredText(body, "data_type");
            require(MASTER_TYPES.contains(type), "无效基础数据类型");
            fields.put("data_type", type);
            fields.put("code", requiredText(body, "code"));
            fields.put("name", requiredText(body, "name"));
        } else {
            var old = db.row("fin_master", id, true);
            type = old.get("data_type").toString();
            require(!fields.containsKey("code") && !body.containsKey("data_type"), "基础数据编码及类型不可修改");
            if (fields.containsKey("name")) {
                fields.put("name", requiredText(body, "name"));
            }
        }
        if (type.equals("汇率") && (id == null || fields.containsKey("rate") || fields.containsKey("effective_date"))) {
            require(decimal(fields, "rate").signum() > 0, "汇率必须大于零");
            fields.put("effective_date", date(fields, "effective_date"));
        }
        if (fields.get("amount_limit") != null) {
            require(type.equals("费用类型") && money(fields, "amount_limit").signum() >= 0, "无效费用限额");
        }
        validateStatus(fields);
        long saved = save("fin_master", id, fields);
        db.audit(actor, "system/master-data", id == null ? "新增基础数据" : "维护基础数据", saved, type);
        return saved;
    }

    /**
     * 校验角色与部门后创建财务账号，并保存派生后的密码。
     */
    public long createUser(Map<String, Object> body, long actor) {
        var fields = fields(body, "department_id role_id");
        db.row("fin_role", id(body, "role_id"), false);
        if (fields.get("department_id") != null) {
            master(id(body, "department_id"), "部门");
        }
        fields.put("username", requiredText(body, "username"));
        fields.put("name", requiredText(body, "name"));
        fields.put("password_hash", AuthService.hash(AuthService.password(body, 12)));
        long id = db.insert("fin_user", fields);
        db.audit(actor, "system/users", "新增用户", id, null);
        return id;
    }

    /**
     * 执行密码重置、停启用或角色分配，并增加会话版本使旧会话失效。
     */
    public void userAction(long id, String action, Map<String, Object> body, long actor) {
        var user = db.row("fin_user", id, true);
        var fields = values("session_version", ((Number) user.get("session_version")).longValue() + 1);
        switch (action) {
            case "reset-password" -> fields.put("password_hash", AuthService.hash(AuthService.password(body, 12)));
            case "disable" -> {
                state(id != actor && !AuthService.admin(user), "不可停用初始管理员");
                fields.put("status", "已停用");
            }
            case "enable" -> fields.put("status", "已启用");
            case "assign-role" -> {
                state(!AuthService.admin(user), "不可修改初始管理员角色");
                fields.put("role_id", id(body, "role_id"));
            }
            default -> require(false, "未知用户操作");
        }
        db.patch("fin_user", id, fields);
        db.audit(actor, "system/users", action, id, null);
    }

    /**
     * 新增或维护角色，保护初始管理员角色。
     */
    public long saveRole(Long id, Map<String, Object> body, long actor) {
        if (id != null) {
            db.row("fin_role", id, true);
            state(db.rows("SELECT id FROM fin_user WHERE role_id=:id AND is_admin=TRUE", values("id", id)).isEmpty(),
                    "初始管理员角色不可修改");
        }
        var fields = fields(body, "name description status");
        fields.put("name", requiredText(body, "name"));
        validateStatus(fields);
        long saved = save("fin_role", id, fields);
        db.audit(actor, "system/permissions", "维护角色", saved, null);
        return saved;
    }

    /**
     * 在事务中整体替换角色的资源及写入授权清单。
     */
    public void permissions(long id, Map<String, Object> body, long actor) {
        db.row("fin_role", id, true);
        require(body.get("permissions") instanceof List<?>, "缺少 permissions 数组");
        var permissions = ((List<?>) body.get("permissions")).isEmpty() ? List.<Map<String, Object>>of() : items(body, "permissions", 40);
        db.update("DELETE FROM fin_permission WHERE role_id=:id", values("id", id));
        for (var entry : permissions) {
            String resource = requiredText(entry, "resource");
            require(FinanceQueryService.known(resource), "无效资源: " + resource);
            require(entry.get("can_write") instanceof Boolean, "can_write 应为布尔值");
            db.update("INSERT INTO fin_permission(role_id,resource,can_write) VALUES (:role,:resource,:write)",
                    values("role", id, "resource", resource, "write", entry.get("can_write")));
        }
        db.audit(actor, "system/permissions", "配置权限", id, null);
    }

    /**
     * 创建草稿流程版本和按顺序排列的审批节点。
     */
    public long createWorkflow(Map<String, Object> body, long actor) {
        var fields = fields(body, "name version department_id expense_type_id min_amount max_amount");
        fields.put("name", requiredText(body, "name"));
        if (body.get("department_id") != null) {
            master(id(body, "department_id"), "部门");
        }
        if (body.get("expense_type_id") != null) {
            master(id(body, "expense_type_id"), "费用类型");
        }
        if (body.get("min_amount") != null) {
            require(money(body, "min_amount").signum() >= 0, "流程最低金额不得为负");
        }
        var steps = items(body, "steps", 30);
        long workflow = db.insert("fin_workflow", fields);
        int stepNo = 1;
        for (var step : steps) {
            long role = id(step, "role_id");
            db.row("fin_role", role, false);
            db.insert("fin_workflow_step", values("workflow_id", workflow, "step_no", stepNo++,
                    "role_id", role, "name", requiredText(step, "name"), "mode", requiredText(step, "mode")));
        }
        db.audit(actor, "system/workflow", "新建流程", workflow, null);
        return workflow;
    }

    /**
     * 校验节点角色拥有有效用户后启用草稿版本。
     */
    public void publishWorkflow(long id, long actor) {
        var row = db.row("fin_workflow", id, true);
        state("草稿".equals(row.get("status")), "流程已发布");
        state(db.rows("""
                SELECT s.id FROM fin_workflow_step s WHERE s.workflow_id=:id AND NOT EXISTS (
                  SELECT 1 FROM fin_user u JOIN fin_role r ON r.id=u.role_id
                  WHERE u.role_id=s.role_id AND u.status='已启用' AND r.status='已启用'
                )
                """, values("id", id)).isEmpty(), "流程存在无有效审批人的节点");
        db.patch("fin_workflow", id, values("status", "已启用"));
        db.audit(actor, "system/workflow", "发布流程", id, null);
    }

    /**
     * 读取并校验基础数据的类型和启用状态。
     */
    Map<String, Object> master(long id, String type) {
        var row = db.row("fin_master", id, false);
        require(type.equals(row.get("data_type")) && "已启用".equals(row.get("status")), "基础数据类型或状态不匹配: " + type);
        return row;
    }

    /**
     * 根据是否给出主键执行新增或字段更新。
     */
    private long save(String table, Long id, Map<String, Object> fields) {
        if (id == null) {
            return db.insert(table, fields);
        }
        db.patch(table, id, fields);
        return id;
    }

    /**
     * 校验给出的启停状态属于允许值。
     */
    private void validateStatus(Map<String, Object> fields) {
        if (fields.containsKey("status")) {
            require(Set.of("已启用", "已停用").contains(requiredText(fields, "status")), "无效启用状态");
        }
    }
}
