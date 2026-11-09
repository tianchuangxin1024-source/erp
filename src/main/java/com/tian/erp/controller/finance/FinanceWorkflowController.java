package com.tian.erp.controller.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tian.erp.common.response.ApiResponse;
import com.tian.erp.service.finance.AssetService;
import com.tian.erp.service.finance.FinanceOperationService;
import com.tian.erp.service.finance.FinanceRuleService;
import com.tian.erp.service.finance.LedgerService;
import com.tian.erp.service.finance.ReconciliationService;
import com.tian.erp.service.finance.YearEndService;
import com.tian.erp.support.finance.FinanceCsv;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.tian.erp.security.FinanceSession.actorId;
import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 处理年度结转、规则配置、CSV 导入、银行对账和专项汇总接口。
 */
@Tag(name = "03 规则、导入与专项查询")
@SecurityRequirement(name = "sessionCookie")
@RestController
@RequestMapping("/api/finance")
public class FinanceWorkflowController {
    private final ReconciliationService reconciliation;
    private final FinanceOperationService operations;
    private final AssetService assets;
    private final FinanceCsv csv;
    private final LedgerService ledger;
    private final FinanceRuleService rules;
    private final YearEndService yearEnd;

    /**
     * 注入年度结转、对账、财务规则和专项查询依赖。
     */
    FinanceWorkflowController(ReconciliationService reconciliation, FinanceOperationService operations,
                              AssetService assets, FinanceCsv csv, LedgerService ledger,
                              FinanceRuleService rules, YearEndService yearEnd) {
        this.reconciliation = reconciliation;
        this.operations = operations;
        this.assets = assets;
        this.csv = csv;
        this.ledger = ledger;
        this.rules = rules;
        this.yearEnd = yearEnd;
    }

    /**
     * 为指定的已结账十二月期间建立下一年期初快照。
     */
    @Operation(summary = "年度结转", description = "id 为已结账十二月期间主键。历史期间须结账，下一年一月已启用。返回 data.id 为结转批次主键。")
    @PostMapping("/gl/closing/{id}/year-carryover")
    ApiResponse<Map<String, Object>> yearCarryover(@Parameter(description = "当前路由操作的业务主键") @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(values("id", yearEnd.carryForward(id, actorId(request))));
    }

    /**
     * 按结转批次查询期初快照及分页余额明细。
     */
    @Operation(summary = "查询年度期初快照", description = "id 为年度结转批次主键。返回 carryover、items、total、page、size。")
    @GetMapping("/gl/closing/year-carryovers/{id}")
    ApiResponse<Map<String, Object>> yearSnapshot(@Parameter(description = "当前路由操作的业务主键") @PathVariable long id, @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(yearEnd.snapshot(id, page, size));
    }

    /**
     * 读取当前科目编码的分段长度规则。
     */
    @Operation(summary = "读取科目编码规则", description = "返回 data.segments，空数组表示尚未配置分段长度。")
    @GetMapping("/gl/accounts/code-rule")
    ApiResponse<Map<String, Object>> accountRule() {
        return ApiResponse.ok(values("segments", rules.accountSegments(false)));
    }

    /**
     * 校验现有科目编码后保存新的分段长度规则。
     */
    @Operation(summary = "配置科目编码规则", description = "segments 为 5–12 个正整数，总长度不超过 64。与现有编码不兼容时拒绝保存。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "五级科目分段", value = "{\"segments\":[4,2,2,2,2]}")
            }))
    @PostMapping("/gl/accounts/code-rule")
    ApiResponse<Map<String, Object>> setAccountRule(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(values("segments", rules.saveAccountSegments(body, actorId(request))));
    }

    /**
     * 保存应收和应付共用的三个账龄上限。
     */
    @Operation(summary = "配置应收应付账龄区间", description = "bounds 必须是三个严格递增正整数，每个不超过 3650，查询按这三个上限划分四档。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "三个账龄上限", value = "{\"bounds\":[30,60,90]}")
            }))
    @PostMapping("/ar/ar-aging/buckets")
    ApiResponse<Map<String, Object>> setAgingRule(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(values("bounds", rules.saveAgingBounds(body, actorId(request))));
    }

    /**
     * 从 UTF-8 CSV 解析科目，按文件行序导入。
     */
    @Operation(summary = "CSV 导入科目", description = "multipart 的 file 为 UTF-8 CSV，最多 2 MB、1000 行。必填列 code,name,category,balance_side，可选 parent_code,dimensions。父科目在子科目前。返回 ids；首个错误使整批回滚。")
    @PostMapping(value = "/gl/accounts/import", consumes = "multipart/form-data")
    ApiResponse<Map<String, Object>> importAccounts(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        var rows = csv.read(file, Set.of("code", "name", "category", "balance_side"), Set.of("parent_code", "dimensions"));
        return ApiResponse.ok(values("ids", ledger.importAccounts(rows, actorId(request))));
    }

    /**
     * 解析银行流水 CSV 并按指定资金账户导入。
     */
    @Operation(summary = "CSV 导入银行流水", description = "multipart 含 file、bank_account_id。UTF-8 CSV 列为 transaction_date,reference_no,summary,direction,amount，最多 2 MB、1000 行。返回 inserted 和 duplicates。")
    @PostMapping(value = "/funds/reconciliation/import", consumes = "multipart/form-data")
    ApiResponse<Map<String, Object>> importStatementsCsv(@RequestParam("file") MultipartFile file, @RequestParam long bank_account_id,
                               HttpServletRequest request) throws IOException {
        var rows = csv.read(file, Set.of("transaction_date", "reference_no", "summary", "direction", "amount"), Set.of());
        return ApiResponse.ok(reconciliation.loadStatements(values("bank_account_id", bank_account_id, "lines", rows), actorId(request)));
    }

    /**
     * 接收 JSON 格式的银行流水并执行批量导入。
     */
    @Operation(summary = "JSON 导入银行流水", description = "lines 1–1000 行。按账户、日期、流水号去重，一致则忽略，内容冲突回滚整批。返回 inserted 和 duplicates。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "银行流水", value = "{\"bank_account_id\":1,\"lines\":[{\"transaction_date\":\"2026-11-06\",\"reference_no\":\"BANK-001\",\"summary\":\"收到款项\",\"direction\":\"收款\",\"amount\":\"100.00\"}]}")
            }))
    @PostMapping(value = "/funds/reconciliation/import", consumes = "application/json")
    ApiResponse<Map<String, Object>> importStatements(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(reconciliation.loadStatements(body, actorId(request)));
    }

    /**
     * 对指定账户和期间的流水执行自动匹配。
     */
    @Operation(summary = "自动匹配银行流水", description = "按账户和期间匹配同日、同方向、同金额及摘要相关的记录，仅保存一对一匹配。返回 matched、conflict_ids。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "自动匹配", value = "{\"bank_account_id\":1,\"period_id\":1}")
            }))
    @PostMapping("/funds/reconciliation/auto-match")
    ApiResponse<Map<String, Object>> match(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(reconciliation.automatic(body, actorId(request)));
    }

    /**
     * 用给定银行期末余额试算余额调节结果。
     */
    @Operation(summary = "生成余额调节结果", description = "必填 bank_account_id、period_id、bank_ending_balance。返回 bank_balance、book_balance、adjusted_bank、adjusted_book、difference、bank_only、book_only、currency。")
    @GetMapping("/funds/reconciliation/adjustment")
    ApiResponse<Map<String, Object>> adjustment(@RequestParam long bank_account_id, @RequestParam long period_id, @RequestParam String bank_ending_balance) {
        return ApiResponse.ok(reconciliation.adjustment(values("bank_account_id", bank_account_id, "period_id", period_id,
                "bank_ending_balance", bank_ending_balance)));
    }

    /**
     * 确认调节平衡后锁定指定账户和会计期间。
     */
    @Operation(summary = "锁定银行对账期间", description = "校验期间开放、账户启用及调节差额为零后锁定账户期间，返回 data.success。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "锁定账户期间", value = "{\"bank_account_id\":1,\"period_id\":1,\"bank_ending_balance\":\"100.00\"}")
            }))
    @PostMapping("/funds/reconciliation/lock")
    ApiResponse<Map<String, Object>> lock(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        reconciliation.lock(body, actorId(request));
        return ApiResponse.ok(values("success", true));
    }

    /**
     * 按日期查询资金计划及账户预计余额。
     */
    @Operation(summary = "资金头寸查询", description = "必填 date(yyyy-MM-dd)，可选 bank_account_id。返回账户、币种、账面余额、计划收付及 projected_balance 数组。")
    @GetMapping("/funds/cash-plans/position")
    ApiResponse<List<Map<String, Object>>> position(@RequestParam String date, @RequestParam(required = false) Long bank_account_id) {
        return ApiResponse.ok(operations.position(values("date", date, "bank_account_id", bank_account_id)));
    }

    /**
     * 按部门和类别筛选并汇总在用资产。
     */
    @Operation(summary = "资产分类部门汇总", description = "可选 department_id、category。汇总使用中资产的 original_value、accumulated_depreciation、net_value。")
    @GetMapping("/fa/asset-cards/summary")
    ApiResponse<List<Map<String, Object>>> assetSummary(@RequestParam(required = false) Long department_id, @RequestParam(required = false) String category) {
        return ApiResponse.ok(assets.summary(department_id, category));
    }
}
