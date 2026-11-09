package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 为已完成年末结账的账务建立下一年度期初余额快照。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class YearEndService {
    private final FinanceRepository db;

    /**
     * 注入年度结转的数据访问组件。
     */
    YearEndService(FinanceRepository db) {
        this.db = db;
    }

    /**
     * 锁定期间时间线和来源期间，校验次年一月启用后保存按科目及辅助维度汇总的余额快照。
     */
    public long carryForward(long periodId, long actor) {
        db.one("SELECT setting_value FROM fin_setting WHERE setting_key='period_timeline' FOR UPDATE", Map.of());
        var period = db.row("fin_period", periodId, true);
        var end = date(period, "end_date");
        int year = end.getYear();
        state(end.equals(LocalDate.of(year, 12, 31)) && "已结账".equals(period.get("status")), "须使用已结账的十二月期间");
        state(db.rows("SELECT id FROM fin_period WHERE period_code<=:code AND status<>'已结账'",
                values("code", period.get("period_code"))).isEmpty(), "仍有未结账的历史期间");
        var next = db.one("SELECT id,status FROM fin_period WHERE period_code=:next FOR UPDATE",
                values("next", String.format(java.util.Locale.ROOT, "%04d-01", year + 1)));
        state("进行中".equals(next.get("status")), "下年度一月期间须已启用");
        long id = db.insert("fin_year_carryover", values("source_period_id", periodId, "target_period_id", next.get("id"), "operator_id", actor));
        db.update("""
                INSERT INTO fin_year_opening (carryover_id,account_id,department_id,project_id,party_id,balance)
                SELECT :id,a.id,l.department_id,l.project_id,l.party_id,
                       SUM(IF(a.balance_side='借',l.debit-l.credit,l.credit-l.debit))
                FROM fin_voucher_line l JOIN fin_account a ON a.id=l.account_id JOIN fin_voucher v ON v.id=l.voucher_id
                WHERE v.voucher_date<=:end AND v.status='已记账'
                GROUP BY a.id,l.department_id,l.project_id,l.party_id HAVING SUM(l.debit-l.credit)<>0
                """, values("id", id, "end", end));
        db.audit(actor, "gl/closing", "年度结转", id, "来源期间 " + period.get("period_code"));
        return id;
    }

    /**
     * 按年度结转主键查询批次信息和分页期初余额。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(long id, int page, int size) {
        require(page >= 1 && size >= 1 && size <= 200, "分页大小应在 1–200 之间");
        var header = db.row("fin_year_carryover", id, false);
        var params = values("id", id, "limit", size, "offset", (long) (page - 1) * size);
        var count = db.one("SELECT COUNT(*) total FROM fin_year_opening WHERE carryover_id=:id", params);
        var rows = db.rows("""
                SELECT o.*,a.code account_code,a.name account_name,a.balance_side
                FROM fin_year_opening o JOIN fin_account a ON a.id=o.account_id
                WHERE o.carryover_id=:id ORDER BY a.code,o.id LIMIT :limit OFFSET :offset
                """, params);
        return values("carryover", header, "items", rows, "total", count.get("total"), "page", page, "size", size);
    }
}
