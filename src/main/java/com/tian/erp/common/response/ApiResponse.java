package com.tian.erp.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatusCode;

/**
 * JSON 接口的统一响应。
 * @param code HTTP 状态码
 * @param message 操作结果或错误原因
 * @param data 业务数据，失败或无业务数据时为 null
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "统一 JSON 响应，成功结果从 data 读取；错误时 data 为 null")
public record ApiResponse<T>(
        @Schema(description = "与 HTTP 状态码一致", example = "200") int code,
        @Schema(description = "操作结果或错误原因", example = "成功") String message,
        @Schema(description = "业务数据，失败或无返回数据时为 null", nullable = true) T data) {
    /**
     * 构造 HTTP 200 对应的成功响应。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "成功", data);
    }

    /**
     * 构造 HTTP 201 对应的创建成功响应。
     */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "创建成功", data);
    }

    /**
     * 按指定 HTTP 状态和消息构造无业务数据的失败响应。
     */
    public static ApiResponse<Void> failure(HttpStatusCode status, String message) {
        return new ApiResponse<>(status.value(), message, null);
    }
}
