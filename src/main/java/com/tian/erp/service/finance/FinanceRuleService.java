package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 管理科目编码分段及应收应付共用的账龄区间规则。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FinanceRuleService {
    private final FinanceRepository db;

    /**
     * 注入规则存储与审计所需的数据访问组件。
     */
    FinanceRuleService(FinanceRepository db) {
        this.db = db;
    }

    /**
     * 读取科目编码分段列表，更新或建科目前可选择锁定规则行。
     */
    public List<Integer> accountSegments(boolean lock) {
        return read("account_segments", lock);
    }

    /**
     * 读取共用的三个账龄上限。
     */
    List<Integer> agingBounds() {
        return read("aging_bounds", false);
    }

    /**
     * 校验五至十二级分段长度及现有科目的兼容性后保存规则。
     */
    public List<Integer> saveAccountSegments(Map<String, Object> body, long actor) {
        var lengths = integers(body, "segments", 5, 12, 64);
        require(lengths.stream().mapToInt(Integer::intValue).sum() <= 64, "科目编码总长度不能超过 64");
        accountSegments(true);
        for (var account : db.rows("SELECT code,account_level FROM fin_account ORDER BY account_level,code", Map.of())) {
            validateAccountCode(lengths, account.get("code").toString(), ((Number) account.get("account_level")).intValue());
        }
        save("account_segments", lengths);
        db.audit(actor, "gl/accounts", "配置编码规则", null, lengths.toString());
        return lengths;
    }

    /**
     * 校验三个严格递增的正整数账龄上限并保存规则。
     */
    public List<Integer> saveAgingBounds(Map<String, Object> body, long actor) {
        var bounds = integers(body, "bounds", 3, 3, 3650);
        require(bounds.get(0) < bounds.get(1) && bounds.get(1) < bounds.get(2), "账龄上限必须严格递增");
        read("aging_bounds", true);
        save("aging_bounds", bounds);
        db.audit(actor, "ar/ar-aging", "配置账龄区间", null, bounds.toString());
        return bounds;
    }

    /**
     * 按科目层级累加分段长度，验证编码长度与规则一致。
     */
    void validateAccountCode(List<Integer> segments, String code, int level) {
        if (!segments.isEmpty()) {
            require(level <= segments.size() && code.length() == segments.subList(0, level).stream().mapToInt(Integer::intValue).sum(),
                    "科目编码不符合配置的分段长度: " + code);
        }
    }

    /**
     * 读取配置字符串并解析整数列表，可在当前事务中锁定配置行。
     */
    private List<Integer> read(String name, boolean lock) {
        var row = db.one("SELECT setting_value FROM fin_setting WHERE setting_key=:name" + (lock ? " FOR UPDATE" : ""),
                values("name", name));
        var value = row.get("setting_value").toString();
        return value.isEmpty() ? List.of() : Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }

    /**
     * 将整数列表以逗号分隔形式写回指定配置。
     */
    private void save(String name, List<Integer> list) {
        String value = list.stream().map(Object::toString).collect(Collectors.joining(","));
        db.update("UPDATE fin_setting SET setting_value=:value WHERE setting_key=:name", values("name", name, "value", value));
    }

    /**
     * 校验指定数组的数量与元素上限，并转换为正整数列表。
     */
    private List<Integer> integers(Map<String, Object> body, String field, int min, int max, int upper) {
        require(body.get(field) instanceof List<?>, "缺少数组: " + field);
        var list = (List<?>) body.get(field);
        require(list.size() >= min && list.size() <= max, "数组长度不符合要求: " + field);
        return list.stream().map(element -> {
            long value = id(values(field, element), field);
            require(value <= upper, "数组值超出范围: " + field);
            return (int) value;
        }).toList();
    }
}
