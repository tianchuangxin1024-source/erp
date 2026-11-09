package com.tian.erp.repository.finance;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.support.finance.FinanceFields.values;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 封装带命名参数的财务 JDBC 操作，由调用方指定受信任的 SQL、表名和字段名。
 */
@Repository
public class FinanceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 注入支持命名参数的 JDBC 模板。
     */
    FinanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 执行查询并按列名返回结果行。
     */
    public List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, params);
    }

    /**
     * 读取查询的首行，无结果时抛出 HTTP 404。
     */
    public Map<String, Object> one(String sql, Map<String, ?> params) {
        var rows = rows(sql, params);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "记录不存在");
        }
        return rows.get(0);
    }

    /**
     * 按主键读取指定表的一行，可在当前事务中加行锁。
     */
    public Map<String, Object> row(String table, long id, boolean lock) {
        return one("SELECT * FROM " + table + " WHERE id=:id" + (lock ? " FOR UPDATE" : ""), values("id", id));
    }

    /**
     * 执行参数化 DML 并返回受影响的行数。
     */
    public int update(String sql, Map<String, ?> params) {
        return jdbc.update(sql, params);
    }

    /**
     * 按字段映射插入记录并返回数据库生成的主键。
     */
    public long insert(String table, Map<String, Object> fields) {
        var columns = String.join(", ", fields.keySet());
        var parameters = fields.keySet().stream().map(name -> ":" + name).collect(Collectors.joining(", "));
        var keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + parameters + ")",
                new MapSqlParameterSource(fields), keys, new String[]{"id"});
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 按主键更新给出的字段，空字段映射直接跳过。
     */
    public void patch(String table, long id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return;
        }
        var assignments = fields.keySet().stream().map(name -> name + "=:" + name).collect(Collectors.joining(", "));
        var params = new LinkedHashMap<>(fields);
        params.put("id", id);
        update("UPDATE " + table + " SET " + assignments + " WHERE id=:id", params);
    }

    /**
     * 记录操作人、资源、动作和业务对象的财务审计事件。
     */
    public void audit(long actor, String resource, String operation, Long id, String detail) {
        insert("fin_audit", values("operator_id", actor, "resource", resource,
                "operation", operation, "object_id", id, "detail", detail));
    }
}
