package cn.yunshu.erp;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.yunshu.erp.Orders.*;

@Service
class PlanCycles {
    private final JdbcTemplate db;
    PlanCycles(JdbcTemplate db) { this.db=db; }

    @Transactional
    void ensure(String iccid,LocalDate today) {
        var cards=db.queryForList("""
            SELECT c.*,p.cycle_months FROM sim_card c JOIN sku s ON s.id=c.sku_id JOIN sim_plan p ON p.iccid=c.iccid
            WHERE c.iccid=? AND s.kind='TERMINAL' AND c.customer_id IS NOT NULL AND c.activated_on IS NOT NULL
            AND c.delivered_on IS NOT NULL AND c.default_quota_kb IS NOT NULL AND c.erp_expiry>=? FOR UPDATE
            """,iccid,today);
        if(cards.isEmpty())return;
        var c=cards.getFirst();
        var delivery=Cards.date(c.get("delivered_on")); var activation=Cards.date(c.get("activated_on"));
        LocalDate anchor=delivery.isAfter(activation)?delivery:activation;
        if(anchor.isAfter(today))return;
        int months=((Number)c.get("cycle_months")).intValue();
        long elapsed=ChronoUnit.MONTHS.between(YearMonth.from(anchor),YearMonth.from(today));
        long period=elapsed/months;
        if(anchor.plusMonths(period*months).isAfter(today))period--;
        LocalDate start=anchor.plusMonths(period*months),end=anchor.plusMonths((period+1)*months);
        long overlapping=db.queryForObject("SELECT COUNT(*) FROM usage_cycle WHERE iccid=? AND starts_on<? AND ends_on>?",Long.class,iccid,end,start);
        if(overlapping==0)db.update("INSERT INTO usage_cycle(iccid,starts_on,ends_on,quota_kb) VALUES(?,?,?,?)",iccid,start,end,c.get("default_quota_kb"));
    }

    // The CT pool counter resets on the first of each calendar month.
    // Each verified baseline pairs that counter with usage already belonging to the customer cycle.
    void applyPoolReading(String iccid,LocalDate today,BigDecimal raw) {
        ensure(iccid,today);
        var cycles=db.queryForList("SELECT starts_on FROM usage_cycle WHERE iccid=? AND starts_on<=? AND ends_on>? FOR UPDATE",iccid,today,today);
        if(cycles.isEmpty())return;
        LocalDate start=Cards.date(cycles.getFirst().get("starts_on")),month=today.withDayOfMonth(1);
        var meters=db.queryForList("SELECT * FROM cycle_meter WHERE iccid=? AND starts_on=? AND month_start=?",iccid,start,month);
        BigDecimal opening=BigDecimal.ZERO,carry=BigDecimal.ZERO;
        if(!meters.isEmpty()){
            opening=(BigDecimal)meters.getFirst().get("raw_baseline"); carry=(BigDecimal)meters.getFirst().get("confirmed_carry");
        } else {
            var card=db.queryForMap("SELECT delivered_on,activated_on FROM sim_card WHERE iccid=?",iccid);
            if(!start.equals(month) || card.get("delivered_on")==null || card.get("activated_on")==null
                || !Cards.date(card.get("delivered_on")).isBefore(start) || !Cards.date(card.get("activated_on")).isBefore(start))return;
        }
        if(raw.compareTo(opening)<0)return;
        var used=raw.subtract(opening).add(carry).setScale(0,RoundingMode.HALF_UP);
        db.update("UPDATE usage_cycle SET carrier_kb=?,carrier_updated_at=CURRENT_TIMESTAMP WHERE iccid=? AND starts_on=?",used,iccid,start);
    }
    void anchor(String iccid,LocalDate starts,BigDecimal baseline,BigDecimal carry) {
        var today=Cards.today();
        require(baseline!=null && carry!=null && baseline.signum()>=0 && carry.signum()>=0,"基线和累计量须为非负数");
        require(db.queryForList("SELECT iccid FROM usage_cycle WHERE iccid=? AND starts_on=? AND starts_on<=? AND ends_on>? FOR UPDATE",iccid,starts,today,today).size()==1,"请选择当前套餐周期");
        db.update("""
            INSERT INTO cycle_meter(iccid,starts_on,month_start,raw_baseline,confirmed_carry) VALUES(?,?,?,?,?)
            ON DUPLICATE KEY UPDATE raw_baseline=?,confirmed_carry=?
            """,iccid,starts,today.withDayOfMonth(1),baseline,carry,baseline,carry);
    }
}
