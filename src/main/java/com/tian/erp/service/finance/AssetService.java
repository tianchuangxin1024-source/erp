package com.tian.erp.service.finance;

import com.tian.erp.common.exception.BusinessException;
import com.tian.erp.repository.finance.FinanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.service.finance.CashService.*;
import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 在事务中管理资产卡片、变动、处置及月度折旧。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AssetService {
    private final FinanceRepository db;
    private final FinanceSystemService system;
    private final LedgerService ledger;

    /**
     * 注入资产持久化、基础数据校验和总账服务。
     */
    AssetService(FinanceRepository db, FinanceSystemService system, LedgerService ledger) {
        this.db = db;
        this.system = system;
        this.ledger = ledger;
    }

    /**
     * 将资产资源的创建请求分派给卡片、变动或处置登记逻辑。
     */
    long create(String resource, Map<String, Object> body, long actor) {
        return switch (resource) {
            case "fa/asset-cards" -> asset(body, actor);
            case "fa/asset-movements" -> movement(body, actor);
            case "fa/asset-disposals" -> disposal(body, actor);
            default -> throw BusinessException.unsupported();
        };
    }

    /**
     * 校验资产部门和折旧参数后保存新资产卡片。
     */
    private long asset(Map<String, Object> body, long actor) {
        system.master(id(body, "department_id"), "部门");
        ledger.period(date(body, "acquisition_date"));
        var fields = fields(body, "code name category department_id custodian_id acquisition_date method life_months "
                + "residual_rate total_units expense_account_id depreciation_account_id asset_account_id");
        for (String key : List.of("code", "name", "category")) {
            fields.put(key, requiredText(body, key));
        }
        var method = requiredText(body, "method");
        if (method.equals("工作量法")) {
            require(decimal(body, "total_units").signum() > 0, "工作量法须填写预计总工作量");
        }
        require(id(body, "expense_account_id") != id(body, "depreciation_account_id")
                && id(body, "asset_account_id") != id(body, "depreciation_account_id"), "资产和折旧科目配置冲突");
        fields.put("original_value", positive(body, "original_value"));
        long id = db.insert("fin_asset", fields);
        db.audit(actor, "fa/asset-cards", "新增资产", id, null);
        return id;
    }

    /**
     * 保存资产原使用信息及目标信息，登记待处理变动。
     */
    private long movement(Map<String, Object> body, long actor) {
        long assetId = id(body, "asset_id");
        var asset = db.row("fin_asset", assetId, true);
        active(asset);
        String type = requiredText(body, "movement_type");
        if (type.equals("调拨")) {
            system.master(id(body, "new_department_id"), "部门");
        }
        if (type.equals("使用人变更")) {
            db.row("fin_user", id(body, "new_custodian_id"), false);
        }
        var date = date(body, "movement_date");
        require(!date.isBefore(date(asset, "acquisition_date")), "变动日期早于资产入账");
        ledger.period(date);
        var fields = fields(body, "asset_id new_department_id new_custodian_id");
        fields.putAll(values("movement_no", orderNo(body, "movement_no", "FM"), "movement_type", type,
                "movement_date", date, "reason", requiredText(body, "reason"), "old_department_id", asset.get("department_id"),
                "old_custodian_id", asset.get("custodian_id")));
        long id = db.insert("fin_asset_movement", fields);
        db.audit(actor, "fa/asset-movements", "申请资产变动", id, null);
        return id;
    }

    /**
     * 核对资产当前使用信息，确认调拨、使用人变更或盘点结果。
     */
    void confirmMovement(long id, Map<String, Object> body, long actor) {
        var record = db.row("fin_asset_movement", id, true);
        long assetId = id(record, "asset_id");
        var asset = db.row("fin_asset", assetId, true);
        active(asset);
        ledger.period(date(record, "movement_date"));
        state("待审批".equals(record.get("status")), "资产变动已处理");
        state(Objects.equals(record.get("old_department_id"), asset.get("department_id"))
                && Objects.equals(record.get("old_custodian_id"), asset.get("custodian_id")), "资产使用信息已变更，请重新申请");
        if ("盘点".equals(record.get("movement_type"))) {
            db.patch("fin_asset_movement", id, values("inventory_result", requiredText(body, "inventory_result"), "status", "已确认"));
        } else {
            var fields = new LinkedHashMap<String, Object>();
            if (record.get("new_department_id") != null) {
                fields.put("department_id", record.get("new_department_id"));
            }
            if (record.get("new_custodian_id") != null) {
                fields.put("custodian_id", record.get("new_custodian_id"));
            }
            db.patch("fin_asset", assetId, fields);
            db.patch("fin_asset_movement", id, values("status", "已确认"));
        }
        db.audit(actor, "fa/asset-movements", "确认资产变动", id, null);
    }

    /**
     * 保存待处理的报废或出售申请，并记录申请时的账面净值。
     */
    private long disposal(Map<String, Object> body, long actor) {
        var asset = db.row("fin_asset", id(body, "asset_id"), true);
        active(asset);
        var date = date(body, "disposal_date");
        require(!date.isBefore(date(asset, "acquisition_date")), "处置日期早于资产入账");
        ledger.period(date);
        state(db.rows("SELECT id FROM fin_asset_disposal WHERE asset_id=:id AND status='待审批'",
                values("id", asset.get("id"))).isEmpty(), "资产已有待处理处置单");
        var proceeds = money(body, "proceeds");
        String type = requiredText(body, "disposal_type");
        require(proceeds.signum() >= 0 && (!type.equals("报废") || proceeds.signum() == 0), "无效处置收入");
        long id = db.insert("fin_asset_disposal", values("asset_id", asset.get("id"),
                "disposal_no", orderNo(body, "disposal_no", "FD"), "disposal_type", type, "disposal_date", date,
                "net_value", net(asset), "proceeds", proceeds, "reason", requiredText(body, "reason")));
        db.audit(actor, "fa/asset-disposals", "申请处置", id, null);
        return id;
    }

    /**
     * 按最新账面金额生成处置凭证，确认处置单并将资产标记为已处置。
     */
    long confirmDisposal(long id, Map<String, Object> body, long actor) {
        var record = db.row("fin_asset_disposal", id, true);
        var asset = db.row("fin_asset", id(record, "asset_id"), true);
        active(asset);
        state("待审批".equals(record.get("status")), "资产处置已处理");
        var date = date(record, "disposal_date");
        var period = ledger.period(date);
        state(db.rows("""
                SELECT d.id FROM fin_depreciation d JOIN fin_period p ON p.id=d.period_id
                WHERE d.asset_id=:asset AND p.period_code>:period
                """, values("asset", asset.get("id"), "period", period.get("period_code"))).isEmpty(), "不能早于已计提期间处置资产");
        var net = net(asset);
        var proceeds = decimal(record, "proceeds");
        var difference = proceeds.subtract(net);
        var lines = new ArrayList<Map<String, Object>>();
        var accumulated = decimal(asset, "accumulated_depreciation");
        var dims = values("department_id", asset.get("department_id"));
        lines.add(line(id(asset, "asset_account_id"), decimal(asset, "original_value"), false, dims));
        if (accumulated.signum() > 0) {
            lines.add(line(id(asset, "depreciation_account_id"), accumulated, true, dims));
        }
        if (proceeds.signum() > 0) {
            lines.add(line(id(body, "proceeds_account_id"), proceeds, true, dims));
        }
        if (difference.signum() != 0) {
            lines.add(line(id(body, "gain_loss_account_id"), difference.abs(), difference.signum() < 0, dims));
        }
        long voucher = ledger.create(period, date, "资产处置 " + record.get("disposal_no"), lines, actor, null, true);
        db.patch("fin_asset_disposal", id, values("status", "已确认", "voucher_id", voucher, "net_value", net));
        db.patch("fin_asset", id(asset, "id"), values("status", "已处置"));
        db.audit(actor, "fa/asset-disposals", "确认处置", id, "凭证 " + voucher);
        return voucher;
    }

    /**
     * 按资产折旧方法计算单资产单期间折旧，保存明细及适用的凭证。
     */
    long depreciate(Map<String, Object> body, long actor) {
        long assetId = id(body, "asset_id");
        var asset = db.row("fin_asset", assetId, true);
        active(asset);
        var period = db.row("fin_period", id(body, "period_id"), true);
        state("进行中".equals(period.get("status")), "会计期间已结账");
        var acquisition = date(asset, "acquisition_date");
        var date = date(period, "end_date");
        var start = date(period, "start_date");
        require(acquisition.isBefore(start), "购置当月不计提折旧");
        state(db.rows("""
                SELECT d.id FROM fin_depreciation d JOIN fin_period p ON p.id=d.period_id
                WHERE d.asset_id=:asset AND p.period_code>=:period
                """, values("asset", assetId, "period", period.get("period_code"))).isEmpty(), "已计提本期或后续期间");
        var original = decimal(asset, "original_value");
        var residual = base(original, decimal(asset, "residual_rate"));
        var available = net(asset).subtract(residual);
        state(available.signum() > 0, "资产已达到残值");
        long life = id(asset, "life_months");
        long elapsed = ChronoUnit.MONTHS.between(java.time.YearMonth.from(acquisition), java.time.YearMonth.from(date));
        var remaining = BigDecimal.valueOf(Math.max(1L, life - elapsed + 1));
        var amount = switch (asset.get("method").toString()) {
            case "工作量法" -> {
                var units = decimal(body, "units");
                require(units.signum() >= 0, "本期工作量不可为负");
                yield original.subtract(residual).multiply(units).divide(decimal(asset, "total_units"), 2, RoundingMode.HALF_UP);
            }
            case "双倍余额递减法" -> remaining.longValue() <= 24
                    ? available.divide(remaining, 2, RoundingMode.HALF_UP)
                    : net(asset).multiply(BigDecimal.valueOf(2)).divide(BigDecimal.valueOf(life), 2, RoundingMode.HALF_UP);
            default -> elapsed >= life ? available
                    : original.subtract(residual).divide(BigDecimal.valueOf(life), 2, RoundingMode.HALF_UP);
        };
        amount = amount.min(available);
        Long voucher = amount.signum() == 0 ? null : depreciationVoucher(asset, period, amount, "计提折旧", actor);
        var total = decimal(asset, "accumulated_depreciation").add(amount);
        long id = db.insert("fin_depreciation", values("asset_id", assetId, "period_id", period.get("id"), "amount", amount,
                "accumulated_amount", total, "voucher_id", voucher));
        db.patch("fin_asset", assetId, values("accumulated_depreciation", total));
        db.audit(actor, "fa/depreciation", "计提折旧", id, null);
        return id;
    }

    /**
     * 在未结账且无后续计提的期间调整折旧，另存调整记录和凭证。
     */
    Long adjust(long id, Map<String, Object> body, long actor) {
        var record = db.row("fin_depreciation", id, true);
        var asset = db.row("fin_asset", id(record, "asset_id"), true);
        active(asset);
        var period = db.row("fin_period", id(record, "period_id"), true);
        state("进行中".equals(period.get("status")), "会计期间已结账");
        state(db.rows("""
                SELECT d.id FROM fin_depreciation d JOIN fin_period p ON p.id=d.period_id
                WHERE d.asset_id=:asset AND p.period_code>:period
                """, values("asset", asset.get("id"), "period", period.get("period_code"))).isEmpty(), "已计提后续期间");
        String reason = requiredText(body, "reason");
        var delta = money(body, "amount");
        var adjusted = decimal(record, "adjustment").add(delta);
        var total = decimal(asset, "accumulated_depreciation").add(delta);
        var limit = decimal(asset, "original_value").subtract(base(decimal(asset, "original_value"), decimal(asset, "residual_rate")));
        require(delta.signum() != 0 && total.signum() >= 0 && total.compareTo(limit) <= 0
                && decimal(record, "amount").add(adjusted).signum() >= 0, "调整后折旧金额超出范围");
        long voucher = depreciationVoucher(asset, period, delta, "调整折旧", actor);
        db.insert("fin_depreciation_adjustment", values("depreciation_id", id, "amount", delta, "reason", reason, "voucher_id", voucher));
        db.patch("fin_depreciation", id, values("adjustment", adjusted, "accumulated_amount", total));
        db.patch("fin_asset", id(asset, "id"), values("accumulated_depreciation", total));
        db.audit(actor, "fa/depreciation", "调整折旧", id, reason);
        return voucher;
    }

    /**
     * 根据折旧金额方向生成费用与累计折旧分录。
     */
    private long depreciationVoucher(Map<String, Object> asset, Map<String, Object> period, BigDecimal amount,
                                      String summary, long actor) {
        var dims = values("department_id", asset.get("department_id"));
        return ledger.create(period, date(period, "end_date"), summary + " " + asset.get("code"),
                List.of(line(id(asset, "expense_account_id"), amount.abs(), amount.signum() > 0, dims),
                        line(id(asset, "depreciation_account_id"), amount.abs(), amount.signum() < 0, dims)), actor, null, true);
    }

    /**
     * 要求资产处于使用中状态，否则拒绝本次操作。
     */
    private void active(Map<String, Object> asset) {
        state("使用中".equals(asset.get("status")), "资产已处置");
    }

    /**
     * 计算资产原值减去累计折旧后的账面净值。
     */
    private BigDecimal net(Map<String, Object> asset) {
        return decimal(asset, "original_value").subtract(decimal(asset, "accumulated_depreciation"));
    }

    /**
     * 按部门和类别汇总在用资产的原值、累计折旧及净值。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summary(Long departmentId, String category) {
        return db.rows("""
                SELECT a.category,d.id department_id,d.name department,SUM(a.original_value) original_value,
                    SUM(a.accumulated_depreciation) accumulated_depreciation,
                    SUM(a.original_value-a.accumulated_depreciation) net_value
                FROM fin_asset a JOIN fin_master d ON d.id=a.department_id WHERE a.status='使用中'
                  AND (:department IS NULL OR a.department_id=:department) AND (:category IS NULL OR a.category=:category)
                GROUP BY a.category,d.id,d.name ORDER BY a.category,d.name
                """, values("department", departmentId, "category", category));
    }
}
