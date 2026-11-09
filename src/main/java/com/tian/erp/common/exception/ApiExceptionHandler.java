package com.tian.erp.common.exception;

import com.tian.erp.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.http.HttpStatus.*;

/**
 * 将 Spring MVC 请求异常和数据库异常转换成统一响应，保留对应的 HTTP 状态。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 将违反唯一约束的请求返回为 HTTP 409。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Object> duplicate(DuplicateKeyException ex, WebRequest request) {
        return respond(ex, "业务编号、来源单据或关联记录已存在", HttpHeaders.EMPTY, CONFLICT, request);
    }

    /**
     * 将字段或引用约束校验失败返回为 HTTP 400。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> invalidRecord(DataIntegrityViolationException ex, WebRequest request) {
        return respond(ex, "字段值、数据约束或引用关系校验失败", HttpHeaders.EMPTY, BAD_REQUEST, request);
    }

    /**
     * 将可重试的数据访问冲突返回为 HTTP 409。
     */
    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<Object> busy(TransientDataAccessException ex, WebRequest request) {
        return respond(ex, "数据正在被处理，请重试", HttpHeaders.EMPTY, CONFLICT, request);
    }

    /**
     * 记录未预期的异常并返回通用的 HTTP 500 消息。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        return respond(ex, "服务器处理请求失败", HttpHeaders.EMPTY, INTERNAL_SERVER_ERROR, request);
    }

    /**
     * 使用框架判定的状态和响应头封装 MVC 异常。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                            HttpStatusCode status, WebRequest request) {
        return respond(ex, message(ex, body, status), headers, status, request);
    }

    /**
     * 生成 JSON 错误体，保留已有响应头并记录服务端异常。
     */
    private ResponseEntity<Object> respond(Exception ex, String message, HttpHeaders headers,
                                           HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("API request failed", ex);
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(ex, ApiResponse.failure(status, message), responseHeaders, status, request);
    }

    /**
     * 为已知请求错误选择面向调用方的消息。
     */
    private String message(Exception ex, Object body, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "服务器处理请求失败";
        }
        if (ex instanceof ResponseStatusException response && response.getReason() != null) {
            return response.getReason();
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "请求体缺失或 JSON 格式无效";
        }
        if (ex instanceof TypeMismatchException || ex instanceof ServletRequestBindingException
                || ex instanceof MissingServletRequestPartException) {
            return "请求参数缺失或格式无效";
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return "上传文件超过大小限制";
        }
        if (ex instanceof NoResourceFoundException) {
            return "接口不存在";
        }
        if (body instanceof ProblemDetail problem && problem.getDetail() != null) {
            return problem.getDetail();
        }
        return "请求失败";
    }
}
