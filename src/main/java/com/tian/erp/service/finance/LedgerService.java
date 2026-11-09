package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 管理会计科目、期间、凭证分录及凭证状态流转。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class LedgerService {
    private final FinanceRepository db;

    private final FinanceRuleService rules;

    /**
     * 注入总账数据访问及编码规则服务。
     */
    LedgerService(FinanceRepository db, FinanceRuleService rules) {
        this.db = db;
        this.rules = rules;
    }

    /**
     * 锁定编码规则，验证父子科目和层级后新增科目。
     */
    public long addAccount(Map<String, Object> body, long actor) {
        var segments = rules.accountSegments(true);
        String code = requiredText(body, "code");
        require(code.matches("[0-9]{1,64}"), "科目编码应为数字");
        var fields = fields(body, "name category balance_side parent_id dimensions");
        fields.put("code", code);
        fields.put("name", requiredText(body, "name"));
        int level = 1;
        if (fields.get("parent_id") != null) {
            long parentId = id(fields, "parent_id");
            var parent = db.row("fin_account", parentId, true);
            String parentCode = parent.get("code").toString();
            require(code.startsWith(parentCode) && code.length() > parentCode.length(), "编码应以前级科目编码开头");
            require(Objects.equals(parent.get("category"), fields.get("category")), "子科目类别须与上级一致");
            state("已启用".equals(parent.get("status")) && !used(parentId), "上级科目已停用或已用于分录");
            level = ((Number) parent.get("account_level")).intValue() + 1;
        }
        rules.validateAccountCode(segments, code, level);
        fields.put("account_level", level);
        long account = db.insert("fin_account", fields);
        db.audit(actor, "gl/accounts", "新增科目", account, code);
        return account;
    }

    /**
     * 按 CSV 行顺序解析上级编码并批量新增科目，首个业务错误带原始行号。
     */
    public List<Long> importAccounts(List<Map<String, Object>> rows, long actor) {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            try {
                if (row.get("parent_code") != null) {
                    var parent = db.one("SELECT id FROM fin_account WHERE code=:code", values("code", row.get("parent_code")));
                    row.put("parent_id", parent.get("id"));
                }
                ids.add(addAccount(row, actor));
            } catch (org.springframework.web.server.ResponseStatusException ex) {
                throw new org.springframework.web.server.ResponseStatusException(ex.getStatusCode(),
                        "CSV 第 " + (i + 2) + " 行: " + ex.getReason(), ex);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                require(false, "CSV 第 " + (i + 2) + " 行科目编码重复或字段不符合约束");
            }
        }
        return ids;
    }

    /**
     * 编辑名称及辅助维度、停用或删除符合条件的科目。
     */
    public void accountAction(long account, String action, Map<String, Object> body, long actor) {
        var old = db.row("fin_account", account, true);
        switch (action) {
            case "edit" -> {
                var change = fields(body, "name dimensions");
                if (change.containsKey("name")) {
                    change.put("name", requiredText(body, "name"));
                }
                db.patch("fin_account", account, change);
            }
            case "disable" -> {
                state(db.rows("SELECT id FROM fin_account WHERE parent_id=:id AND status='已启用'",
                        values("id", account)).isEmpty(), "请先停用下级科目");
                db.patch("fin_account", account, values("status", "已停用"));
            }
            case "delete" -> {
                state(!used(account) && db.rows("SELECT id FROM fin_account WHERE parent_id=:id",
                        values("id", account)).isEmpty(), "科目存在历史分录或下级科目");
                db.update("DELETE FROM fin_account WHERE id=:id", values("id", account));
            }
            default -> require(false, "未知科目操作");
        }
        db.audit(actor, "gl/accounts", action, account, old.get("code").toString());
    }

    /**
     * 检查科目是否已有历史凭证分录。
     */
    private boolean used(long account) {
        return !db.rows("SELECT id FROM fin_voucher_line WHERE account_id=:id LIMIT 1", values("id", account)).isEmpty();
    }

    /**
     * 校验完整自然月并串行校验年度时间范围后启用期间。
     */
    public long openPeriod(Map<String, Object> body, long actor) {
        String code = requiredText(body, "period_code");
        LocalDate start = date(body, "start_date");
        LocalDate end = date(body, "end_date");
        require(YearMonth.from(start).toString().equals(code) && start.getDayOfMonth() == 1
                && end.equals(YearMonth.from(start).atEndOfMonth()), "会计期间应为完整自然月");
        db.one("SELECT setting_value FROM fin_setting WHERE setting_key='period_timeline' FOR UPDATE", Map.of());
        state(db.rows("""
                SELECT y.id FROM fin_year_carryover y JOIN fin_period p ON p.id=y.source_period_id
                WHERE p.period_code>=:code
                """, values("code", code)).isEmpty(), "不能在已完成年度结转的范围内补开期间");
        long id = db.insert("fin_period", values("period_code", code, "start_date", start, "end_date", end));
        db.audit(actor, "system/periods", "启用期间", id, code);
        return id;
    }

    /**
     * 按业务日期读取并锁定开放期间，校验该日期位于期间范围。
     */
    Map<String, Object> period(LocalDate date) {
        var period = db.one("SELECT * FROM fin_period WHERE period_code=:code FOR UPDATE",
                values("code", YearMonth.from(date).toString()));
        state("进行中".equals(period.get("status")), "会计期间已结账");
        state(!date.isBefore(date(period, "start_date")) && !date.isAfter(date(period, "end_date")), "日期不在会计期间内");
        return period;
    }

    /**
     * 按日期和分录新增草稿凭证。
     */
    public long addVoucher(Map<String, Object> body, long actor) {
        var date = date(body, "voucher_date");
        return create(period(date), date, requiredText(body, "summary"), items(body, "lines", 500), actor, null, false);
    }

    /**
     * 在已锁定期间内分配连续流水号，保存凭证及分录，并按调用参数选择草稿或待审核状态。
     */
    long create(Map<String, Object> period, LocalDate date, String summary, List<Map<String, Object>> lines,
                long actor, Long reversalOf, boolean submit) {
        var normalized = normalize(lines, reversalOf != null);
        if (submit) {
            balanced(normalized);
        }
        long periodId = id(period, "id");
        int number = ((Number) period.get("next_voucher_no")).intValue();
        String code = "记-" + period.get("period_code") + "-" + String.format(Locale.ROOT, "%04d", number);
        long voucher = db.insert("fin_voucher", values("period_id", periodId, "serial_no", number, "voucher_no", code,
                "voucher_date", date, "summary", summary, "debit_total", normalized.debit, "credit_total", normalized.credit,
                "maker_id", actor, "reversal_of", reversalOf, "status", submit ? "待审核" : "草稿"));
        db.patch("fin_period", periodId, values("next_voucher_no", number + 1));
        writeLines(voucher, normalized);
        db.audit(actor, "gl/vouchers", submit ? "生成待审核凭证" : "新增凭证", voucher, code);
        return voucher;
    }

    /**
     * 封装已校验的分录行、借方合计和贷方合计。
     */
    private record Lines(List<Map<String, Object>> rows, BigDecimal debit, BigDecimal credit) {}

    /**
     * 校验分录科目、金额及必填辅助维度，返回规范化分录和借贷合计。
     */
    private Lines normalize(List<Map<String, Object>> lines, boolean reversal) {
        var rows = new ArrayList<Map<String, Object>>();
        var debitTotal = BigDecimal.ZERO;
        var creditTotal = BigDecimal.ZERO;
        var accountIds = new TreeSet<Long>();
        for (var line : lines) {
            accountIds.add(id(line, "account_id"));
        }
        var accounts = new HashMap<Long, Map<String, Object>>();
        for (long id : accountIds) {
            var account = db.row("fin_account", id, true);
            if (!reversal) {
                state("已启用".equals(account.get("status")) &&
                        db.rows("SELECT id FROM fin_account WHERE parent_id=:id LIMIT 1", values("id", id)).isEmpty(),
                        "只能引用已启用的末级科目");
            }
            accounts.put(id, account);
        }
        for (var line : lines) {
            var fields = fields(line, "account_id summary debit credit department_id project_id party_id");
            var debit = line.get("debit") == null ? BigDecimal.ZERO : money(line, "debit");
            var credit = line.get("credit") == null ? BigDecimal.ZERO : money(line, "credit");
            require((debit.signum() == 0) != (credit.signum() == 0), "每条分录仅可填写一方金额");
            require(reversal || debit.signum() >= 0 && credit.signum() >= 0, "普通分录金额不得为负");
            var account = accounts.get(id(line, "account_id"));
            if (!reversal) {
                String dimensions = Objects.toString(account.get("dimensions"), "");
                for (var dimension : Map.of("部门", "department_id", "项目", "project_id", "客户", "party_id", "供应商", "party_id").entrySet()) {
                    if (dimensions.contains(dimension.getKey())) {
                        long dimensionId = id(line, dimension.getValue());
                        var master = db.row("fin_master", dimensionId, false);
                        require(dimension.getKey().equals(master.get("data_type")) && "已启用".equals(master.get("status")),
                                "辅助核算维度无效: " + dimension.getKey());
                    }
                }
            }
            debitTotal = debitTotal.add(debit);
            creditTotal = creditTotal.add(credit);
            fields.put("debit", debit);
            fields.put("credit", credit);
            rows.add(fields);
        }
        return new Lines(rows, debitTotal, creditTotal);
    }

    /**
     * 要求至少两条分录、借方非零且借贷合计相等。
     */
    private void balanced(Lines lines) {
        require(lines.rows.size() >= 2 && lines.debit.signum() != 0 && lines.debit.compareTo(lines.credit) == 0,
                "凭证至少需要两条分录且借贷平衡");
    }

    /**
     * 按行序保存凭证分录。
     */
    private void writeLines(long voucher, Lines lines) {
        int number = 1;
        for (var row : lines.rows) {
            row.put("voucher_id", voucher);
            row.put("line_no", number++);
            db.insert("fin_voucher_line", row);
        }
    }

    /**
     * 按凭证主键和行序读取原始分录。
     */
    private List<Map<String, Object>> voucherLines(long id) {
        return db.rows("SELECT * FROM fin_voucher_line WHERE voucher_id=:id ORDER BY line_no", values("id", id));
    }

    /**
     * 执行编辑、提交、审核、记账、复制、红冲或作废，并校验操作者、期间及来源业务限制。
     */
    public Long voucherAction(long voucherId, String action, Map<String, Object> body, long actor) {
        var snapshot = db.row("fin_voucher", voucherId, false);
        if (action.equals("copy")) {
            var date = date(body, "voucher_date");
            return create(period(date), date, snapshot.get("summary").toString(), voucherLines(voucherId), actor, null, false);
        }
        var voucherDate = date(snapshot, "voucher_date");
        var period = period(voucherDate);
        var voucher = db.row("fin_voucher", voucherId, true);
        String status = voucher.get("status").toString();
        switch (action) {
            case "edit" -> {
                state(status.equals("草稿"), "仅草稿可编辑");
                state(id(voucher, "maker_id") == actor, "仅制单人可编辑");
                LocalDate date = body.containsKey("voucher_date") ? date(body, "voucher_date") : voucherDate;
                require(YearMonth.from(date).equals(YearMonth.from(voucherDate)), "修改日期不可跨期间");
                var lines = normalize(items(body, "lines", 500), false);
                db.update("DELETE FROM fin_voucher_line WHERE voucher_id=:id", values("id", voucherId));
                writeLines(voucherId, lines);
                db.patch("fin_voucher", voucherId, values("voucher_date", date, "summary", requiredText(body, "summary"),
                        "debit_total", lines.debit, "credit_total", lines.credit));
            }
            case "submit" -> {
                state(status.equals("草稿") && id(voucher, "maker_id") == actor, "仅制单人可提交草稿");
                balanced(normalize(voucherLines(voucherId), false));
                db.patch("fin_voucher", voucherId, values("status", "待审核"));
            }
            case "approve" -> {
                state(status.equals("待审核"), "仅待审核凭证可审核");
                state(id(voucher, "maker_id") != actor, "制单人不可审核本人凭证");
                balanced(normalize(voucherLines(voucherId), voucher.get("reversal_of") != null));
                db.patch("fin_voucher", voucherId, values("status", "已审核", "reviewer_id", actor));
            }
            case "post" -> {
                state(status.equals("已审核"), "仅已审核凭证可记账");
                balanced(normalize(voucherLines(voucherId), voucher.get("reversal_of") != null));
                db.update("UPDATE fin_voucher SET status='已记账',posted_by=:actor,posted_at=CURRENT_TIMESTAMP WHERE id=:id",
                        values("id", voucherId, "actor", actor));
            }
            case "void" -> {
                state(Set.of("草稿", "待审核").contains(status), "仅草稿和待审核凭证可作废");
                state(id(voucher, "maker_id") == actor, "仅制单人可作废");
                state(db.rows("""
                        SELECT id FROM fin_trade WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_cash_order WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_settlement WHERE voucher_id=:id OR reversal_voucher_id=:id
                        UNION ALL SELECT id FROM fin_depreciation WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_asset_disposal WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_bad_debt WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_claim WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_depreciation_adjustment WHERE voucher_id=:id
                        """, values("id", voucherId)).isEmpty(), "请通过来源业务处理凭证");
                db.patch("fin_voucher", voucherId, values("status", "已作废"));
            }
            case "reverse" -> {
                state(status.equals("已记账") && voucher.get("reversal_of") == null, "只能冲销原始记账凭证");
                state(db.rows("""
                        SELECT id FROM fin_trade WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_cash_order WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_settlement WHERE voucher_id=:id OR reversal_voucher_id=:id
                        UNION ALL SELECT id FROM fin_depreciation WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_asset_disposal WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_bad_debt WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_claim WHERE voucher_id=:id
                        UNION ALL SELECT id FROM fin_depreciation_adjustment WHERE voucher_id=:id
                        """, values("id", voucherId)).isEmpty(), "请通过来源业务处理凭证");
                return reverse(voucher, period, date(body, "voucher_date"), actor);
            }
            default -> require(false, "未知凭证操作");
        }
        db.audit(actor, "gl/vouchers", action, voucherId, voucher.get("voucher_no").toString());
        return null;
    }

    /**
     * 在目标开放期间生成关联原凭证的红字待审核凭证。
     */
    long reverse(Map<String, Object> voucher, Map<String, Object> originalPeriod, LocalDate date, long actor) {
        require(!date.isBefore(date(voucher, "voucher_date")), "冲销日期不得早于原凭证");
        var targetPeriod = YearMonth.from(date).toString().equals(originalPeriod.get("period_code")) ? originalPeriod : period(date);
        long voucherId = id(voucher, "id");
        var lines = voucherLines(voucherId);
        for (var line : lines) {
            line.put("debit", decimal(line, "debit").negate());
            line.put("credit", decimal(line, "credit").negate());
        }
        return create(targetPeriod, date, "红字冲销 " + voucher.get("voucher_no"), lines, actor, voucherId, true);
    }

    /**
     * 按期间的下一个流水号和现有凭证列出缺号。
     */
    public Map<String, Object> gaps(long periodId) {
        var period = db.row("fin_period", periodId, false);
        var seen = new HashSet<Integer>();
        for (var row : db.rows("SELECT serial_no FROM fin_voucher WHERE period_id=:id", values("id", periodId))) {
            seen.add(((Number) row.get("serial_no")).intValue());
        }
        var missing = new ArrayList<Integer>();
        int next = ((Number) period.get("next_voucher_no")).intValue();
        for (int number = 1; number < next; number++) {
            if (!seen.contains(number)) {
                missing.add(number);
            }
        }
        return values("period_code", period.get("period_code"), "missing", missing);
    }
}
