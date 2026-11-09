package com.tian.erp.controller.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tian.erp.common.response.ApiResponse;
import com.tian.erp.security.FinanceSession;
import com.tian.erp.service.finance.AttachmentService;
import com.tian.erp.service.finance.FinanceAnalyticsService;
import com.tian.erp.service.finance.FinanceQueryService;
import com.tian.erp.support.finance.FinanceCsv;
import com.tian.erp.support.finance.FinanceWorkbook;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 提供凭证和报销附件的上传、下载及财务查询结果导出。
 */
@Tag(name = "04 附件与导出")
@SecurityRequirement(name = "sessionCookie")
@RestController
@RequestMapping("/api/finance")
public class FinanceFileController {
    private final AttachmentService attachments;
    private final FinanceQueryService queries;
    private final FinanceAnalyticsService analytics;

    /**
     * 注入附件服务和导出所需的查询服务。
     */
    FinanceFileController(AttachmentService attachments, FinanceQueryService queries, FinanceAnalyticsService analytics) {
        this.attachments = attachments;
        this.queries = queries;
        this.analytics = analytics;
    }

    /**
     * 接收 multipart 文件并保存为凭证或报销单附件。
     */
    @Operation(summary = "上传凭证或报销附件", description = "仅支持 gl/vouchers、expense/claims。multipart 字段 file，每份不超过 10 MB，单据最多 10 份，允许 PDF/PNG/JPEG。仅本人草稿可上传，返回 data.id。")
    @PostMapping("/{module:gl|expense}/{page:vouchers|claims}/{id}/attachments")
    ApiResponse<Map<String, Object>> upload(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id,
                  @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        return ApiResponse.ok(values("id", attachments.upload(module + "/" + page, id, file, FinanceSession.actorId(request))));
    }

    /**
     * 校验附件归属及记录可见性，返回原始文件字节。
     */
    @Operation(summary = "下载单据附件", description = "校验单据可见性与附件归属，成功返回原始字节及 Content-Disposition。")
    @GetMapping("/{module:gl|expense}/{page:vouchers|claims}/{id}/attachments/{attachmentId}")
    ResponseEntity<byte[]> download(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page, @Parameter(description = "当前路由操作的业务主键") @PathVariable long id,
                                    @Parameter(description = "附件主键") @PathVariable long attachmentId, HttpServletRequest request) {
        var file = attachments.download(module + "/" + page, id, attachmentId, FinanceSession.actor(request));
        return file((byte[]) file.get("file_data"), file.get("file_name").toString(), MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * 按可访问的审批任务下载其对应报销单的附件。
     */
    @Operation(summary = "审批任务附件下载", description = "id 为当前可见的审批任务主键，通过其报销单读取附件。成功返回原始字节。")
    @GetMapping("/expense/approvals/{id}/attachments/{attachmentId}")
    ResponseEntity<byte[]> approvalAttachment(@Parameter(description = "当前路由操作的业务主键") @PathVariable long id, @Parameter(description = "附件主键") @PathVariable long attachmentId, HttpServletRequest request) {
        var file = attachments.download("expense/approvals", id, attachmentId, FinanceSession.actor(request));
        return file((byte[]) file.get("file_data"), file.get("file_name").toString(), MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * 沿用列表或报表筛选生成 XLSX 或 CSV，普通列表超过 200 条时拒绝导出。
     */
    @Operation(summary = "导出业务查询结果", description = "沿用对应页面的筛选字段，format=xlsx(默认)或csv。常规列表最多200条，明细账最多2000条。成功直接返回文件，错误返回统一 JSON。自定义报表需 period_code 和 report_id。")
    @GetMapping("/{module}/{page}/export")
    ResponseEntity<byte[]> export(@Parameter(description = "业务模块，例如 gl、ar、ap、funds、expense、fa、reports、system") @PathVariable String module, @Parameter(description = "业务页面标识，须与模块匹配，参照接口说明") @PathVariable String page,
                                  @Parameter(hidden = true) @RequestParam Map<String, String> filters, HttpServletRequest request) {
        String resource = module + "/" + page;
        var actor = FinanceSession.actor(request);
        var query = new LinkedHashMap<>(filters);
        String format = query.remove("format");
        require(format == null || Set.of("xlsx", "csv").contains(format), "支持 xlsx 和 csv 导出");
        List<Map<String, Object>> rows = switch (resource) {
            case "gl/ledgers", "ar/ar-aging", "ap/ap-aging", "reports/balance-sheet",
                 "reports/income-statement", "reports/cash-flow" -> reportRows(analytics.query(resource, query));
            case "reports/custom-reports" -> reportRows(analytics.report(page, query));
            default -> {
                var result = queries.list(resource, query, 1, 200, actor);
                require(result.total() <= 200, "导出记录超过 200 条，请缩小筛选范围");
                yield result.items();
            }
        };
        if ("csv".equals(format)) {
            return file(FinanceCsv.encode(rows), page + ".csv", MediaType.parseMediaType("text/csv;charset=UTF-8"));
        }
        return file(FinanceWorkbook.encode(rows), page + ".xlsx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    /**
     * 提取报表或汇总结果中的明细记录供文件编码使用。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reportRows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("items");
    }

    /**
     * 设置下载文件名、媒体类型及长度，并写入原始字节。
     */
    private ResponseEntity<byte[]> file(byte[] bytes, String name, MediaType mediaType) {
        return ResponseEntity.ok().contentType(mediaType).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
