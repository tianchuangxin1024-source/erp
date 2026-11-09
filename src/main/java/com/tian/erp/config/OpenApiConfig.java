package com.tian.erp.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 为 springdoc 配置会话认证、统一错误响应与动态查询参数说明。
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "ERP 财务服务 API", version = "1.0",
        description = "JSON 接口统一返回 code、message、data，code 与 HTTP 状态码一致。"
                + "先使用登录接口建立会话，再在同源 Swagger UI 调用财务接口。"
                + "文档中的示例主键需替换为数据库实际主键。历史用户档案接口独立于财务账号。"))
@SecurityScheme(name = "sessionCookie", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE, paramName = "JSESSIONID",
        description = "通过 POST /api/auth/login 登录后浏览器自动携带会话 Cookie。"
                + "HttpOnly Cookie 由服务端设置，请在同源 Swagger UI 登录。")
public class OpenApiConfig {
    /**
     * 添加当前 API 的错误协议和 Map 查询参数，文件成功响应使用二进制 Schema。
     */
    @Bean
    OperationCustomizer apiDocumentation() {
        return (operation, handler) -> {
            var responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            for (var error : Map.of(
                    "400", "请求字段、日期金额、引用关系或 JSON 校验失败",
                    "401", "未登录、账号密码错误或会话已失效",
                    "403", "资源权限、操作权限或请求来源校验失败",
                    "404", "资源、业务记录或接口不存在",
                    "409", "单据状态、唯一约束、余额校验或并发冲突",
                    "500", "服务器处理请求失败").entrySet()) {
                var example = new java.util.LinkedHashMap<String, Object>();
                example.put("code", Integer.valueOf(error.getKey()));
                example.put("message", error.getValue());
                example.put("data", null);
                responses.addApiResponse(error.getKey(), new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description(error.getValue())
                        .content(new Content().addMediaType("application/json",
                                new io.swagger.v3.oas.models.media.MediaType()
                                        .schema(new Schema<>().type("object")
                                                .addProperty("code", new IntegerSchema().example(Integer.valueOf(error.getKey())))
                                                .addProperty("message", new StringSchema().example(error.getValue()))
                                                .addProperty("data", new Schema<>().nullable(true)))
                                        .example(example))));
            }
            String method = handler.getMethod().getName();
            switch (method) {
                case "dashboard" -> queries(operation, "period_code bank_account_id currency");
                case "list" -> {
                    queries(operation, "q status category parent_id account_level period_code voucher_no summary "
                            + "voucher_date party_id party due_date currency transaction_date account_name bank_account_id direction "
                            + "match_status cycle applicant applicant_id department department_id expense_type approval_status "
                            + "payment_status claim_id code name asset_code movement_type disposal_type treatment username "
                            + "data_type fiscal_year operator resource operation result operated_at backup_at "
                            + "start_period end_period ledger_type account_id project_id cutoff_date comparison report_id");
                    operation.setDescription(operation.getDescription()
                            + "\n\n筛选仅使用当前资源支持的字段。日期字段可使用 _from、_to 闭区间，"
                            + "例如 due_date_from、due_date_to。常规列表按 id 倒序，page 从 1 开始，size 为 1–200。");
                }
                case "customReport" -> queries(operation, "period_code comparison");
                case "export" -> {
                    queries(operation, "format period_code report_id q status category currency start_period end_period ledger_type");
                    operation.setDescription(operation.getDescription() + " 其它筛选可在请求 URL 中按对应页面的字段添加。");
                    binary(operation, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv");
                }
                case "download", "approvalAttachment" -> binary(operation, "application/octet-stream");
                default -> { }
            }
            return operation;
        };
    }

    /** 添加可选的动态筛选参数，已有的显式参数优先。 */
    private void queries(Operation operation, String names) {
        for (String name : names.split(" ")) {
            boolean exists = operation.getParameters() != null && operation.getParameters().stream()
                    .anyMatch(parameter -> name.equals(parameter.getName()) && "query".equals(parameter.getIn()));
            if (!exists) {
                operation.addParametersItem(new Parameter().name(name).in("query").required(false)
                        .description("按当前资源支持的字段筛选，日期 yyyy-MM-dd、期间 yyyy-MM；不适用时留空。")
                        .schema(new StringSchema()));
            }
        }
    }

    /** 将下载成功响应定义为原始文件，错误响应仍沿用统一 JSON。 */
    private void binary(Operation operation, String... mediaTypes) {
        var content = new Content();
        for (String mediaType : mediaTypes) {
            content.addMediaType(mediaType, new io.swagger.v3.oas.models.media.MediaType()
                    .schema(new StringSchema().format("binary")));
        }
        operation.getResponses().addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()
                .description("原始文件字节，Content-Disposition 包含下载文件名").content(content));
    }
}
