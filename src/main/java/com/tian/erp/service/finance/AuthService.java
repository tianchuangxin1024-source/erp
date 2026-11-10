package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.support.finance.FinanceFields.*;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 校验登录凭据、读取会话授权并初始化首个管理员。
 */
@Service
public class AuthService {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final FinanceRepository db;

    /**
     * 注入财务用户及角色的数据访问组件。
     */
    AuthService(FinanceRepository db) {
        this.db = db;
    }

    /** 自主注册仅使用账号和密码，普通注册角色的业务权限由管理员维护。 */
    @Transactional
    public Map<String, Object> register(Map<String, Object> body) {
        String username = requiredText(body, "username");
        String encoded = hash(password(body, 1));
        db.update("INSERT IGNORE INTO fin_role(name,description) VALUES ('注册用户','自主注册的普通用户')", Map.of());
        var role = db.one("SELECT id,status FROM fin_role WHERE name='注册用户'", Map.of());
        require("已启用".equals(role.get("status")), "注册暂不可用，请联系管理员");
        long userId;
        try {
            userId = db.insert("fin_user", values("username", username, "name", username,
                    "role_id", role.get("id"), "password_hash", encoded, "is_admin", false));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "账号已存在");
        }
        db.audit(userId, "system/users", "自主注册", userId, null);
        return values("id", userId, "username", username);
    }
    /**
     * 按启用状态检索用户并验证密码，返回供会话建立使用的内部用户记录。
     */
    public Map<String, Object> authenticate(Map<String, Object> body) {
        String username = requiredText(body, "username");
        String password = password(body, 1);
        var users = db.rows("""
                SELECT u.* FROM fin_user u JOIN fin_role r ON r.id=u.role_id
                WHERE u.username=:username AND u.status='已启用' AND r.status='已启用'
                """, values("username", username));
        var user = users.isEmpty() ? Map.<String, Object>of() : users.get(0);
        String encoded = (String) user.get("password_hash");
        if (!verify(password, encoded) || user.isEmpty()) {
            throw new ResponseStatusException(UNAUTHORIZED, "账号或密码错误");
        }
        return user;
    }

    /**
     * 按用户主键与会话版本读取仍有效的用户和角色，失效时返回空映射。
     */
    public Map<String, Object> sessionUser(Object id, Object version) {
        var users = db.rows("""
                SELECT u.* FROM fin_user u JOIN fin_role r ON r.id=u.role_id
                WHERE u.id=:id AND u.session_version=:version AND u.status='已启用' AND r.status='已启用'
                """, values("id", id, "version", version));
        return users.isEmpty() ? Map.of() : users.get(0);
    }

    /**
     * 检查角色是否拥有指定资源的读取或写入权限。
     */
    public boolean canAccess(Object role, String resource, boolean read) {
        return !db.rows("""
                SELECT role_id FROM fin_permission
                WHERE role_id=:role AND resource=:resource AND (:reading=TRUE OR can_write=TRUE)
                """, values("role", role, "resource", resource, "reading", read)).isEmpty();
    }

    /**
     * 在尚无管理员且提供初始凭据时创建系统管理员角色及账号。
     */
    @Transactional
    public void bootstrap(String username, String password) {
        if (!db.rows("SELECT id FROM fin_user WHERE is_admin=TRUE LIMIT 1", Map.of()).isEmpty()) {
            return;
        }
        require(username.length() <= 64 && !username.isBlank(), "无效初始管理员账号");
        String encoded = hash(password(values("password", password), 12));
        db.update("INSERT INTO fin_role(name) VALUES ('系统管理员') ON DUPLICATE KEY UPDATE name=VALUES(name)", Map.of());
        long role = id(db.one("SELECT id FROM fin_role WHERE name='系统管理员'", Map.of()), "id");
        db.insert("fin_user", values("username", username, "name", "财务管理员", "role_id", role,
                "password_hash", encoded, "is_admin", true));
    }

    /**
     * 校验密码参数类型及长度，并保留密码中的原始空白字符。
     */
    static String password(Map<String, Object> body, int minLength) {
        Object value = body.get("password");
        require(value instanceof String, "缺少密码");
        String password = (String) value;
        require(password.length() >= minLength && password.length() <= 256, "密码长度不符合要求");
        return password;
    }

    /**
     * 使用随机盐和 PBKDF2WithHmacSHA256 生成密码存储值。
     */
    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(derive(password, salt));
    }

    /**
     * 对输入密码进行派生，并以恒定时间比较摘要。
     */
    private static boolean verify(String password, String encoded) {
        if (encoded == null) {
            derive(password, new byte[16]);
            return false;
        }
        String[] parts = encoded.split(":");
        byte[] actual = derive(password, Base64.getDecoder().decode(parts[0]));
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(parts[1]));
    }

    /**
     * 使用配置的迭代次数和盐派生 256 位密码摘要。
     */
    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 兼容数据库布尔及数值表示，判断是否为初始管理员。
     */
    public static boolean admin(Map<String, Object> actor) {
        Object flag = actor.get("is_admin");
        return Boolean.TRUE.equals(flag) || flag instanceof Number number && number.intValue() != 0;
    }

    /**
     * 生成对外用户资料，包含角色、部门和资源授权清单。
     */
    public Map<String, Object> profile(Map<String, Object> user) {
        return values("id", user.get("id"), "username", user.get("username"), "name", user.get("name"),
                "role_id", user.get("role_id"), "department_id", user.get("department_id"), "is_admin", admin(user),
                "permissions", db.rows("SELECT resource,can_write FROM fin_permission WHERE role_id=:role",
                        values("role", user.get("role_id"))));
    }

}
