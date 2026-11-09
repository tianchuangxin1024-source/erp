package com.tian.erp.controller.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tian.erp.common.response.ApiResponse;
import com.tian.erp.security.FinanceSession;
import com.tian.erp.service.finance.AuthService;
import com.tian.erp.service.finance.CashService;
import com.tian.erp.service.finance.FinanceAnalyticsService;
import com.tian.erp.service.finance.FinanceOperationService;
import com.tian.erp.service.finance.FinanceQueryService;
import com.tian.erp.service.finance.FinanceSystemService;
import com.tian.erp.service.finance.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.common.exception.BusinessException.unsupported;
import static com.tian.erp.security.FinanceSession.actorId;
import static com.tian.erp.support.finance.FinanceFields.*;
import static org.springframework.http.HttpStatus.*;

/**
 * 处理财务通用查询、新增、行操作及总账、往来和报表的专用查询。
 */
@Tag(name = "02 财务通用与业务操作")
@SecurityRequirement(name = "sessionCookie")
@RestController
@RequestMapping("/api/finance")
public class FinanceController {
    private final FinanceQueryService queries;
    private final FinanceAnalyticsService analytics;
    private final LedgerService ledger;
    private final CashService cash;
    private final FinanceSystemService system;
    private final FinanceOperationService operations;

    /**
     * 注入页面查询、分析和各业务写入服务。
     */
    FinanceController(FinanceQueryService queries, FinanceAnalyticsService analytics, LedgerService ledger, CashService cash,
                      FinanceSystemService system, FinanceOperationService operations) {
        this.queries = queries;
        this.analytics = analytics;
        this.ledger = ledger;
        this.cash = cash;
        this.system = system;
        this.operations = operations;
    }

    /**
     * 按会计期间、资金账户和币种查询工作台汇总。
     */
    @Operation(summary = "财务工作台", description = "可选 period_code(yyyy-MM)、bank_account_id、currency。data 包含 period、balances、monthly、trend、tasks、activities；balances 是当前余额。")
    @GetMapping("/dashboard")
    ApiResponse<Map<String, Object>> dashboard(@Parameter(hidden = true) @RequestParam Map<String, String> filters, HttpServletRequest request) {
        return ApiResponse.ok(analytics.dashboard(filters, FinanceSession.actor(request)));
    }

    /**
     * 列出系统角色可配置的财务资源标识。
     */
    @Operation(summary = "可授权资源清单", description = "返回可分配的 resource 字符串数组。普通用户需要 system/permissions 的读取权限。")
    @GetMapping("/system/permissions/resources")
    ApiResponse<Set<String>> permissionResources() {
        return ApiResponse.ok(FinanceQueryService.resources());
    }

    /**
     * 按资源类型查询分页记录、账簿、账龄或标准报表。
     */
    @Operation(summary = "查询业务页面", description = "module/page 组合对应 FINANCE_API.md 的页面表。常规列表返回 data.items、total、page、size。未知筛选字段返回 400。q 仅支持科目、凭证、资产卡片、用户和基础资料。账簿需要 start_period；标准报表需要 period_code，可选 comparison、report_id；账龄可选 cutoff_date、party_id、account_id、department_id、project_id。")
    @GetMapping("/{module}/{page}")
    ApiResponse<?> list(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(hidden = true) @RequestParam Map<String, String> filters,
                @RequestParam(name = "page", defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int size,
                HttpServletRequest request) {
        String resource = module + "/" + page;
        var actor = FinanceSession.actor(request);
        return ApiResponse.ok(switch (resource) {
            case "gl/ledgers", "ar/ar-aging", "ap/ap-aging", "reports/balance-sheet", "reports/income-statement", "reports/cash-flow"
                    -> analytics.query(resource, filters);
            default -> queries.list(resource, filters, pageNumber, size, actor);
        });
    }

    /**
     * 按资源和主键读取详情以及适用的明细和关联记录。
     */
    @Operation(summary = "查询单据详情", description = "按数字主键读取常规页面记录，凭证含 lines/attachments，往来含 settlements，报销含 lines/tasks/attachments，审批含 lines/history/attachments，资产含折旧/变动/处置，角色含 permissions。分析资源使用对应专用查询。")
    @GetMapping("/{module}/{page}/{id:\\d+}")
    ApiResponse<Map<String, Object>> detail(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(queries.detail(module + "/" + page, id, FinanceSession.actor(request)));
    }

    /**
     * 按期间主键检查凭证流水号的缺号。
     */
    @Operation(summary = "凭证断号检查", description = "periodId 是会计期间数据库主键，返回 period_code 与 missing 数组。")
    @GetMapping("/gl/vouchers/periods/{periodId}/gaps")
    ApiResponse<Map<String, Object>> gaps(@Parameter(description = "会计期间主键") @PathVariable long periodId) {
        return ApiResponse.ok(ledger.gaps(periodId));
    }

    /**
     * 返回指定期间能否结账及阻塞结账的业务记录。
     */
    @Operation(summary = "结账检查", description = "返回 ready、pending_vouchers、imbalanced_vouchers、missing_depreciation、pending_trade、profit_and_loss。")
    @GetMapping("/gl/closing/{id}/check")
    ApiResponse<Map<String, Object>> checkClosing(@Parameter(description = "当前路由操作的业务主键") @PathVariable long id) {
        return ApiResponse.ok(analytics.closingCheck(id));
    }

    /**
     * 为已确认收款或付款单查找同往来单位、同币种同汇率的可核销单据。
     */
    @Operation(summary = "核销候选方案", description = "仅支持 ar/collections 和 ap/payments。按往来、币种、汇率筛选，返回最多 100 条候选及 suggested_amount；通过 settle 动作确认。")
    @GetMapping("/{module:ar|ap}/{page:collections|payments}/{id}/suggestions")
    ApiResponse<List<Map<String, Object>>> suggestions(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id) {
        require(Set.of("ar/collections", "ap/payments").contains(module + "/" + page), "页面不匹配");
        return ApiResponse.ok(cash.suggestions(module.toUpperCase(Locale.ROOT), id));
    }

    /**
     * 按模板、行次和期间下钻到报表公式及相关已记账分录。
     */
    @Operation(summary = "报表行联查分录", description = "page 支持 balance-sheet、income-statement、cash-flow、custom-reports。必填 period_code。返回 terms、items、total，明细含 voucher_id 与 contribution，最多 2000 条。")
    @GetMapping("/reports/{page:balance-sheet|income-statement|cash-flow|custom-reports}/{id}/lines/{lineNo}/ledger")
    ApiResponse<Map<String, Object>> reportDetails(@Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id, @Parameter(description = "报表行次，正整数") @PathVariable int lineNo,
                         @RequestParam String period_code) {
        return ApiResponse.ok(analytics.reportDetails(page, id, lineNo, period_code));
    }

    /**
     * 使用 URL 中的模板主键查询自定义报表结果。
     */
    @Operation(summary = "运行自定义报表", description = "必填 period_code(yyyy-MM)，可选 comparison(同比或环比，默认环比)。模板主键取 URL 中的 id。")
    @GetMapping("/reports/custom-reports/{id}/run")
    ApiResponse<Map<String, Object>> customReport(@Parameter(description = "当前路由操作的业务主键") @PathVariable long id, @Parameter(hidden = true) @RequestParam Map<String, String> filters) {
        var params = new LinkedHashMap<>(filters);
        params.put("report_id", Long.toString(id));
        return ApiResponse.ok(analytics.report("custom-reports", params));
    }

    /**
     * 分派受支持的资源创建请求，返回新记录主键。
     */
    @Operation(summary = "创建业务单据或配置", description = "请先选择与 module/page 对应的请求示例。支持 gl/accounts、gl/vouchers、ar/receivables、ar/collections、ar/bad-debts、ap/payables、ap/payments、ap/notes、funds/bank-accounts、funds/cash-orders、funds/cash-plans、expense/claims、expense/loans、fa/asset-cards、fa/depreciation、fa/asset-movements、fa/asset-disposals、四类 reports 页面、system/users、system/permissions、system/master-data、system/periods、system/workflow。返回 HTTP 201 和 data.id。示例主键需替换为实际数据。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "科目 gl/accounts", value = "{\"code\":\"1002\",\"name\":\"银行存款\",\"category\":\"资产\",\"balance_side\":\"借\"}"),
                    @ExampleObject(name = "凭证 gl/vouchers", value = "{\"voucher_date\":\"2026-11-06\",\"summary\":\"收到客户款项\",\"lines\":[{\"account_id\":1,\"debit\":\"100.00\"},{\"account_id\":2,\"credit\":\"100.00\",\"party_id\":3}]}"),
                    @ExampleObject(name = "应收 ar/receivables 或应付 ap/payables", value = "{\"party_id\":3,\"order_date\":\"2026-11-06\",\"due_date\":\"2026-12-06\",\"amount\":\"100.00\",\"trade_account_id\":2,\"counterpart_account_id\":4,\"currency\":\"CNY\",\"exchange_rate\":1}"),
                    @ExampleObject(name = "资金账户 funds/bank-accounts", value = "{\"name\":\"基本账户\",\"bank_name\":\"示例银行\",\"account_number\":\"123456789\",\"account_type\":\"银行账户\",\"ledger_account_id\":1,\"currency\":\"CNY\",\"opening_balance\":\"0.00\"}"),
                    @ExampleObject(name = "普通收付 funds/cash-orders", value = "{\"direction\":\"收款\",\"party_id\":3,\"bank_account_id\":1,\"counterpart_account_id\":2,\"transaction_date\":\"2026-11-06\",\"amount\":\"100.00\",\"purpose\":\"收到款项\"}"),
                    @ExampleObject(name = "预收 ar/collections 或预付 ap/payments", value = "{\"party_id\":3,\"bank_account_id\":1,\"counterpart_account_id\":2,\"transaction_date\":\"2026-11-06\",\"amount\":\"100.00\",\"purpose\":\"登记预收或预付\"}"),
                    @ExampleObject(name = "报销 expense/claims", value = "{\"department_id\":1,\"expense_type_id\":2,\"lines\":[{\"expense_type_id\":2,\"account_id\":3,\"expense_date\":\"2026-11-06\",\"amount\":\"100.00\",\"purpose\":\"办公用品\"}]}"),
                    @ExampleObject(name = "借款 expense/loans", value = "{\"purpose\":\"差旅借款\",\"amount\":\"100.00\",\"due_date\":\"2026-12-01\",\"loan_account_id\":3}"),
                    @ExampleObject(name = "资产 fa/asset-cards", value = "{\"code\":\"FA-001\",\"name\":\"办公设备\",\"category\":\"办公设备\",\"department_id\":1,\"acquisition_date\":\"2026-11-06\",\"original_value\":\"1200.00\",\"method\":\"平均年限法\",\"life_months\":12,\"residual_rate\":\"0.05\",\"asset_account_id\":4,\"expense_account_id\":5,\"depreciation_account_id\":6}"),
                    @ExampleObject(name = "折旧 fa/depreciation", value = "{\"asset_id\":1,\"period_id\":2}"),
                    @ExampleObject(name = "报表 reports 下四种页面", value = "{\"name\":\"利润表\",\"lines\":[{\"line_no\":1,\"label\":\"营业收入\",\"account_id\":4,\"metric\":\"贷方发生\",\"factor\":1}]}"),
                    @ExampleObject(name = "用户 system/users", value = "{\"username\":\"accountant\",\"name\":\"会计\",\"role_id\":2,\"department_id\":1,\"password\":\"请设置至少12字符的新密码\"}"),
                    @ExampleObject(name = "角色 system/permissions", value = "{\"name\":\"会计\",\"description\":\"凭证与账簿维护\"}"),
                    @ExampleObject(name = "流程 system/workflow", value = "{\"name\":\"报销审批\",\"version\":1,\"steps\":[{\"role_id\":2,\"name\":\"财务审批\",\"mode\":\"串行\"}]}"),
                    @ExampleObject(name = "期间 system/periods", value = "{\"period_code\":\"2026-11\",\"start_date\":\"2026-11-01\",\"end_date\":\"2026-11-30\"}"),
                    @ExampleObject(name = "基础资料 system/master-data", value = "{\"data_type\":\"客户\",\"code\":\"C001\",\"name\":\"示例客户\"}"),
                    @ExampleObject(name = "票据 ap/notes", value = "{\"note_no\":\"NOTE-001\",\"party_id\":3,\"note_type\":\"银行承兑\",\"amount\":\"100.00\",\"issued_on\":\"2026-11-06\",\"due_date\":\"2026-12-06\"}"),
                    @ExampleObject(name = "资金计划 funds/cash-plans", value = "{\"bank_account_id\":1,\"cycle\":\"月度\",\"start_date\":\"2026-11-01\",\"end_date\":\"2026-11-30\",\"planned_receipts\":\"1000.00\",\"planned_payments\":\"500.00\"}"),
                    @ExampleObject(name = "坏账 ar/bad-debts", value = "{\"trade_id\":1,\"transaction_date\":\"2026-11-06\",\"treatment\":\"计提\",\"amount\":\"10.00\",\"expense_account_id\":5,\"allowance_account_id\":6}"),
                    @ExampleObject(name = "资产变动 fa/asset-movements", value = "{\"asset_id\":1,\"movement_type\":\"调拨\",\"new_department_id\":2,\"movement_date\":\"2026-11-06\",\"reason\":\"部门调整\"}"),
                    @ExampleObject(name = "资产处置 fa/asset-disposals", value = "{\"asset_id\":1,\"disposal_type\":\"出售\",\"disposal_date\":\"2026-11-06\",\"proceeds\":\"100.00\",\"reason\":\"设备更新\"}")
            }))
    @PostMapping("/{module}/{page}")
    @ResponseStatus(CREATED)
    ApiResponse<Map<String, Object>> create(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @RequestBody Map<String, Object> body,
                  HttpServletRequest request) {
        String resource = module + "/" + page;
        long actor = actorId(request);
        long id = switch (resource) {
            case "gl/accounts" -> ledger.addAccount(body, actor);
            case "gl/vouchers" -> ledger.addVoucher(body, actor);
            case "ar/receivables", "ap/payables" -> cash.addTrade(module.toUpperCase(Locale.ROOT), body, actor, false);
            case "funds/bank-accounts" -> cash.addBankAccount(body, actor);
            case "funds/cash-orders", "ar/collections", "ap/payments" -> cash.addCashOrder(resource, body, actor);
            case "system/users" -> system.createUser(body, actor);
            case "system/permissions" -> system.saveRole(null, body, actor);
            case "system/master-data" -> system.saveMaster(null, body, actor);
            case "system/periods" -> ledger.openPeriod(body, actor);
            case "system/workflow" -> system.createWorkflow(body, actor);
            case "reports/balance-sheet", "reports/income-statement", "reports/cash-flow", "reports/custom-reports"
                    -> analytics.configureReport(page, null, body, actor);
            default -> operations.create(resource, body, actor);
        };
        return ApiResponse.created(values("id", id));
    }

    /**
     * 按资源、记录和动作分派业务操作，并返回该动作产生的记录主键。
     */
    @Operation(summary = "执行业务行操作", description = "action 按资源使用：科目 edit/disable/delete；凭证 edit/submit/approve/post/copy/reverse/void；结账 transfer-profit/close/reopen；往来 approve；收付款 settle/reverse-settlement；账户 edit；普通资金单 approve；报销 submit；借款 approve/repay；审批 approve/reject/transfer/add-signer；报销支付 pay；资产变动和处置 confirm；折旧 adjust；票据 redeem；银行流水 match；用户 reset-password/disable/enable/assign-role；角色 edit/configure；基础资料 edit；流程 publish；报表 configure。返回 data.success 与可空 result_id，其含义按动作是凭证或业务记录主键。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "无参数 submit approve post void disable enable publish close", value = "{}"),
                    @ExampleObject(name = "编辑凭证 edit", value = "{\"summary\":\"修改摘要\",\"lines\":[{\"account_id\":1,\"debit\":\"100.00\"},{\"account_id\":2,\"credit\":\"100.00\"}]}"),
                    @ExampleObject(name = "核销 settle", value = "{\"trade_id\":1,\"amount\":\"100.00\",\"settled_on\":\"2026-11-06\",\"request_key\":\"allocation-001\"}"),
                    @ExampleObject(name = "反核销 reverse-settlement", value = "{\"settlement_id\":1,\"reversed_on\":\"2026-11-06\"}"),
                    @ExampleObject(name = "凭证复制 copy 或红冲 reverse", value = "{\"voucher_date\":\"2026-11-06\"}"),
                    @ExampleObject(name = "损益结转 transfer-profit", value = "{\"profit_account_id\":10}"),
                    @ExampleObject(name = "反结账 reopen", value = "{\"reason\":\"补录调整\"}"),
                    @ExampleObject(name = "角色 configure", value = "{\"permissions\":[{\"resource\":\"gl/vouchers\",\"can_write\":true}]}"),
                    @ExampleObject(name = "角色 edit", value = "{\"name\":\"会计\",\"description\":\"凭证维护\",\"status\":\"已启用\"}"),
                    @ExampleObject(name = "重置密码 reset-password", value = "{\"password\":\"请设置至少12字符的新密码\"}"),
                    @ExampleObject(name = "分配角色 assign-role", value = "{\"role_id\":2}"),
                    @ExampleObject(name = "审批驳回 reject", value = "{\"opinion\":\"请补充凭证\"}"),
                    @ExampleObject(name = "审批转交 transfer 或加签 add-signer", value = "{\"assignee_id\":3,\"opinion\":\"请协助审核\"}"),
                    @ExampleObject(name = "报销支付 pay", value = "{\"bank_account_id\":1,\"transaction_date\":\"2026-11-06\"}"),
                    @ExampleObject(name = "借款 approve repay 或票据 redeem", value = "{\"cash_order_id\":1}"),
                    @ExampleObject(name = "折旧 adjust", value = "{\"amount\":\"10.00\",\"reason\":\"折旧更正\"}"),
                    @ExampleObject(name = "处置 confirm", value = "{\"proceeds_account_id\":3,\"gain_loss_account_id\":4}"),
                    @ExampleObject(name = "盘点 confirm", value = "{\"inventory_result\":\"账实相符\"}"),
                    @ExampleObject(name = "手工勾对 match", value = "{\"cash_order_id\":1}"),
                    @ExampleObject(name = "基础数据 edit", value = "{\"name\":\"更新名称\",\"status\":\"已启用\"}"),
                    @ExampleObject(name = "报表 configure", value = "{\"name\":\"利润表\",\"lines\":[{\"line_no\":1,\"label\":\"营业收入\",\"account_id\":4,\"metric\":\"贷方发生\",\"factor\":1}]}")
            }))
    @PostMapping("/{module}/{page}/{id:\\d+}/{action}")
    ApiResponse<Map<String, Object>> action(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id, @Parameter(description = "该业务资源支持的动作，参照接口说明") @PathVariable String action,
                  @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        var params = body == null ? Map.<String, Object>of() : body;
        String resource = module + "/" + page;
        long actor = actorId(request);
        Long result = switch (resource) {
            case "gl/accounts" -> { ledger.accountAction(id, action, params, actor); yield null; }
            case "gl/vouchers" -> ledger.voucherAction(id, action, params, actor);
            case "gl/closing" -> {
                if (action.equals("reopen") && !AuthService.admin(FinanceSession.actor(request))) {
                    throw new ResponseStatusException(FORBIDDEN, "反结账需要管理员权限");
                }
                yield analytics.close(id, action, params, actor);
            }
            case "funds/bank-accounts" -> {
                require(action.equals("edit"), "未知资金账户操作");
                cash.editBankAccount(id, params, actor);
                yield null;
            }
            case "funds/cash-orders" -> {
                require(action.equals("approve"), "未知收付操作");
                yield cash.confirmCashOrder(id, actor);
            }
            case "ar/receivables", "ap/payables" -> {
                require(action.equals("approve"), "未知往来操作");
                yield cash.approveTrade(module.toUpperCase(Locale.ROOT), id, actor);
            }
            case "ar/collections", "ap/payments" -> {
                var side = module.toUpperCase(Locale.ROOT);
                yield switch (action) {
                    case "settle" -> cash.settle(side, id, params, actor);
                    case "reverse-settlement" -> cash.reverseSettlement(side, id, params, actor);
                    default -> throw unsupported();
                };
            }
            case "system/users" -> { system.userAction(id, action, params, actor); yield null; }
            case "system/permissions" -> {
                switch (action) {
                    case "edit" -> system.saveRole(id, params, actor);
                    case "configure" -> system.permissions(id, params, actor);
                    default -> throw unsupported();
                }
                yield null;
            }
            case "system/master-data" -> {
                require(action.equals("edit"), "未知基础数据操作");
                yield system.saveMaster(id, params, actor);
            }
            case "system/workflow" -> {
                require(action.equals("publish"), "未知审批流操作");
                system.publishWorkflow(id, actor);
                yield null;
            }
            case "reports/balance-sheet", "reports/income-statement", "reports/cash-flow", "reports/custom-reports" -> {
                require(action.equals("configure"), "未知报表操作");
                yield analytics.configureReport(page, id, params, actor);
            }
            default -> operations.action(resource, id, action, params, FinanceSession.actor(request));
        };
        return ApiResponse.ok(values("success", true, "result_id", result));
    }

    /**
     * 使用来源类型和来源编号录入应收或应付单据。
     */
    @Operation(summary = "同步来源应收或应付单", description = "仅支持 ar/receivables 和 ap/payables。由调用方提供来源单据字段，source_type/source_no 用于唯一校验；重复来源返回 409。成功返回 data.id。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "来源单据", value = "{\"source_type\":\"销售发票\",\"source_no\":\"INV-001\",\"party_id\":3,\"order_date\":\"2026-11-06\",\"due_date\":\"2026-12-06\",\"amount\":\"100.00\",\"trade_account_id\":2,\"counterpart_account_id\":4}")
            }))
    @PostMapping("/{module:ar|ap}/{page:receivables|payables}/sync")
    ApiResponse<Map<String, Object>> sync(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(Set.of("ar/receivables", "ap/payables").contains(module + "/" + page), "页面不匹配");
        return ApiResponse.ok(values("id", cash.addTrade(module.toUpperCase(Locale.ROOT), body, actorId(request), true)));
    }

    /**
     * 对凭证主键去重后逐笔审核或记账，汇总成功和业务失败结果。
     */
    @Operation(summary = "批量审核或记账凭证", description = "action 仅为 approve 或 post。ids 非空且最多 200 个，先去重再逐笔处理。HTTP 200 时仍需检查 data.items[].success 和 message。制单人不得自审。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "凭证主键数组", value = "{\"ids\":[1,2]}")
            }))
    @PostMapping("/gl/vouchers/batch/{action:approve|post}")
    ApiResponse<Map<String, Object>> batch(@Parameter(description = "该业务资源支持的动作，参照接口说明") @PathVariable String action, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(body.get("ids") instanceof List<?>, "缺少 ids 数组");
        var ids = (List<?>) body.get("ids");
        require(!ids.isEmpty() && ids.size() <= 200, "批量操作最多 200 条");
        var results = new ArrayList<Map<String, Object>>();
        var unique = new LinkedHashSet<Long>();
        for (Object item : ids) {
            unique.add(id(values("id", item), "id"));
        }
        for (long id : unique) {
            try {
                ledger.voucherAction(id, action, Map.of(), actorId(request));
                results.add(values("id", id, "success", true));
            } catch (ResponseStatusException ex) {
                results.add(values("id", id, "success", false, "message", ex.getReason()));
            } catch (TransientDataAccessException ex) {
                results.add(values("id", id, "success", false, "message", "数据正在被处理，请重试"));
            }
        }
        return ApiResponse.ok(values("items", results));
    }
}
