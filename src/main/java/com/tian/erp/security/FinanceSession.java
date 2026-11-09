package com.tian.erp.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import static com.tian.erp.support.finance.FinanceFields.id;

/**
 * 管理财务登录会话及请求级用户资料。
 */
public final class FinanceSession {
    static final String USER_ID = "financeUser";
    static final String VERSION = "financeVersion";
    static final String ACTOR = FinanceSession.class.getName();

    /**
     * 工具类构造器，禁止实例化。
     */
    private FinanceSession() {}

    /**
     * 使旧会话失效并在新会话中记录用户主键和会话版本。
     */
    public static void start(HttpServletRequest request, Map<String, Object> user) {
        end(request);
        var session = request.getSession(true);
        session.setAttribute(USER_ID, id(user, "id"));
        session.setAttribute(VERSION, user.get("session_version"));
    }

    /**
     * 注销存在的 HTTP 会话。
     */
    public static void end(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /**
     * 读取鉴权拦截器写入当前请求的用户资料。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> actor(HttpServletRequest request) {
        return (Map<String, Object>) request.getAttribute(ACTOR);
    }

    /**
     * 返回当前请求中已认证用户的主键。
     */
    public static long actorId(HttpServletRequest request) {
        return id(actor(request), "id");
    }
}
