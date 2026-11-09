package com.tian.erp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tian.erp.common.response.ApiResponse;
import com.tian.erp.security.FinanceSession;
import com.tian.erp.service.finance.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 提供登录、会话查询和退出接口。
 */
@Tag(name = "01 登录与会话")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    /**
     * 注入负责凭据校验和用户资料查询的服务。
     */
    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 验证用户名和密码，建立新会话并返回用户资料及授权。
     */
    @Operation(summary = "登录", description = "验证启用用户与角色，建立新的 JSESSIONID 会话。登录后在同一浏览器的 Swagger UI 调试财务接口。密码以实际初始化值为准。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = {
                    @ExampleObject(name = "账号密码", value = "{\"username\":\"admin\",\"password\":\"请填写初始化时设置的密码\"}")
            }))
    @PostMapping("/login")
    ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        var user = auth.authenticate(body);
        FinanceSession.start(request, user);
        return ApiResponse.ok(auth.profile(user));
    }

    /**
     * 读取当前有效会话中的用户资料及授权。
     */
    @Operation(summary = "查询当前会话", description = "返回 id、username、name、role_id、department_id、is_admin 和 permissions。会话过期或版本失效返回 401。")
    @SecurityRequirement(name = "sessionCookie")
    @GetMapping("/session")
    ApiResponse<Map<String, Object>> session(HttpServletRequest request) {
        return ApiResponse.ok(auth.profile(FinanceSession.actor(request)));
    }

    /**
     * 注销现有会话并返回无数据的成功响应。
     */
    @Operation(summary = "退出登录", description = "使当前会话失效，返回 HTTP 200，data 为 null。")
    @PostMapping("/logout")
    ApiResponse<Void> logout(HttpServletRequest request) {
        FinanceSession.end(request);
        return ApiResponse.ok(null);
    }
}
