package com.tian.erp.common.exception;

import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 携带 HTTP 状态的业务异常，用于拒绝未实现的资源操作。
 */
public final class BusinessException extends ResponseStatusException {
    /**
     * 创建 HTTP 404 业务异常。
     */
    private BusinessException(String reason) {
        super(NOT_FOUND, reason);
    }

    /**
     * 生成未知业务操作的异常。
     */
    public static BusinessException unsupported() {
        return new BusinessException("未知业务操作");
    }
}
