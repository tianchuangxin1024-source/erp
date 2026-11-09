package com.tian.erp.config;

import com.tian.erp.security.FinanceAuthInterceptor;
import com.tian.erp.service.finance.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册财务会话拦截器和首个管理员的初始化任务。
 */
@Configuration
public class FinanceWebConfig implements WebMvcConfigurer {
    private final FinanceAuthInterceptor interceptor;

    /**
     * 注入财务请求拦截器。
     */
    public FinanceWebConfig(FinanceAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * 配置初始管理员账号和密码，在应用启动时执行有条件的初始化。
     */
    @Bean
    ApplicationRunner financeBootstrap(AuthService auth,
            @Value("${erp.bootstrap.username:admin}") String username,
            @Value("${erp.bootstrap.password:}") String password) {
        return args -> {
            if (!password.isEmpty()) {
                auth.bootstrap(username, password);
            }
        };
    }

    /**
     * 将会话和来源校验应用到认证及财务 API 路径。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/finance/**", "/api/auth/**");
    }
}
