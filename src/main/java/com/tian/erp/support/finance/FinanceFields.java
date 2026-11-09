package com.tian.erp.support.finance;

import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 提供财务 Map 参数的字段提取、金额日期解析和业务校验工具。
 */
public final class FinanceFields {
    /**
     * 工具类构造器，禁止实例化。
     */
    private FinanceFields() {}

    /**
     * 按交替给出的键和值构建保持插入顺序的参数映射。
     */
    public static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /**
     * 仅复制允许名单中的简单类型字段，保留显式 null。
     */
    public static Map<String, Object> fields(Map<String, Object> body, String allowed) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : allowed.split(" ")) {
            if (body.containsKey(key)) {
                Object value = body.get(key);
                require(value == null || value instanceof String || value instanceof Number || value instanceof Boolean,
                        "无效字段: " + key);
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 要求参数为非空白字符串并去除首尾空白。
     */
    public static String requiredText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        require(value instanceof String && !value.toString().isBlank(), "缺少字段: " + key);
        return value.toString().strip();
    }

    /**
     * 将主键参数解析为正整数 long，拒绝小数和越界值。
     */
    public static long id(Map<String, Object> row, String key) {
        try {
            long id = new BigDecimal(String.valueOf(row.get(key))).longValueExact();
            require(id > 0, "无效标识: " + key);
            return id;
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "无效标识: " + key);
        }
    }

    /**
     * 将指定字段解析为 BigDecimal，缺失或不可解析时拒绝请求。
     */
    public static BigDecimal decimal(Map<String, Object> row, String key) {
        try {
            return new BigDecimal(String.valueOf(row.get(key)));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "无效金额: " + key);
        }
    }

    /**
     * 校验金额最多两位小数且整数部分不超过十七位。
     */
    public static BigDecimal money(Map<String, Object> row, String key) {
        BigDecimal amount = decimal(row, key).stripTrailingZeros();
        require(amount.scale() <= 2 && amount.precision() - amount.scale() <= 17, "无效金额精度: " + key);
        return amount;
    }

    /**
     * 按 yyyy-MM-dd 解析业务日期。
     */
    public static LocalDate date(Map<String, Object> row, String key) {
        try {
            return LocalDate.parse(String.valueOf(row.get(key)));
        } catch (java.time.DateTimeException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "日期格式应为 yyyy-MM-dd: " + key);
        }
    }

    /**
     * 条件不满足时抛出带业务消息的 HTTP 400 异常。
     */
    public static void require(boolean valid, String message) {
        if (!valid) {
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
    }

    /**
     * 状态条件不满足时抛出带业务消息的 HTTP 409 异常。
     */
    public static void state(boolean valid, String message) {
        if (!valid) {
            throw new ResponseStatusException(CONFLICT, message);
        }
    }

    /**
     * 校验非空且未超过上限的对象数组，并复制明细映射。
     */
    public static List<Map<String, Object>> items(Map<String, Object> body, String key, int max) {
        require(body.get(key) instanceof List<?>, "缺少明细: " + key);
        var list = (List<?>) body.get(key);
        require(!list.isEmpty() && list.size() <= max, "明细数量超限: " + key);
        var result = new ArrayList<Map<String, Object>>();
        for (var item : list) {
            require(item instanceof Map<?, ?>, "无效明细: " + key);
            var row = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) item).forEach((name, value) -> row.put((String) name, value));
            result.add(row);
        }
        return result;
    }
}
