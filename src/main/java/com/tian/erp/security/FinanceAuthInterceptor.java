package com.tian.erp.security;

import com.tian.erp.service.finance.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 对财务及认证请求执行来源、会话有效性和资源权限检查。
 */
@Component
public class FinanceAuthInterceptor implements HandlerInterceptor {
    private final AuthService auth;

    /**
     * 注入会话用户查询及资源权限服务。
     */
    public FinanceAuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 在控制器运行前加载会话用户并校验该方法所需的资源权限。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (!read) {
            checkOrigin(request);
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api/auth/login") || path.equals("/api/auth/logout")) {
            return true;
        }
        var session = request.getSession(false);
        if (session == null || session.getAttribute(FinanceSession.USER_ID) == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        }
        var user = auth.sessionUser(session.getAttribute(FinanceSession.USER_ID), session.getAttribute(FinanceSession.VERSION));
        if (user.isEmpty()) {
            session.invalidate();
            throw new ResponseStatusException(UNAUTHORIZED, "会话已失效");
        }
        request.setAttribute(FinanceSession.ACTOR, user);
        if (!AuthService.admin(user) && !path.equals("/api/auth/session")) {
            String[] parts = path.split("/");
            String resource = parts.length >= 5 ? parts[3] + "/" + parts[4] : "dashboard";
            if ((!read && resource.startsWith("system/")) || !auth.canAccess(user.get("role_id"), resource, read)) {
                throw new ResponseStatusException(FORBIDDEN, "无权访问该功能");
            }
        }
        return true;
    }

    /**
     * 在写请求带有 Origin 时校验它与服务端识别的来源一致。
     */
    private void checkOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return;
        }
        String expected = request.getScheme() + "://" + request.getServerName();
        int port = request.getServerPort();
        if (!(port == 80 && "http".equals(request.getScheme()))
                && !(port == 443 && "https".equals(request.getScheme()))) {
            expected += ":" + port;
        }
        if (!expected.equals(origin)) {
            throw new ResponseStatusException(FORBIDDEN, "请求来源不匹配");
        }
    }
}
