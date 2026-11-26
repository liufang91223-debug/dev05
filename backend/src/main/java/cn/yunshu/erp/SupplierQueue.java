package cn.yunshu.erp;

import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 供应商入库回填任务的查询与人工重试入口。 */
@RestController
@RequestMapping("/api")
class SupplierQueue {
    private static final Set<String> STATUSES=Set.of("QUEUED","RUNNING","SUCCEEDED","PARTIAL",
        "RETRYABLE_FAILED","FAILED","PERMANENT_FAILED");

    private final JdbcTemplate db;
    private final Orders orders;
    SupplierQueue(JdbcTemplate db,Orders orders) { this.db=db; this.orders=orders; }

    @GetMapping("/erp/supplier/queue")
    Object queue(@RequestParam(required=false) String status,@RequestParam(defaultValue="100") int limit) {
        Orders.require(limit>=1 && limit<=500,"查询条数需在 1 至 500 之间");
        String where="";
        Object[] args;
        if(status==null || status.isBlank()) { args=new Object[]{limit}; }
        else {
            Orders.require(STATUSES.contains(status),"回填状态无效");
            where=" WHERE q.last_status=? "; args=new Object[]{status,limit};
        }
        return db.queryForList("""
            SELECT q.iccid,q.operation,q.request_id,q.requested_at,q.attempts,q.last_status,q.next_attempt,
                   q.lease_until,q.last_error,q.error_class,q.completed_at,q.updated_at,
                   c.msisdn,c.imsi,c.imei,c.last_reconciled_at,c.purchase_order_id,
                   a.id AS carrier_account_id,a.name AS carrier_name,a.adapter_key,
                   s.name AS sku_name,u.username AS requested_by
            FROM carrier_sync_queue q
            JOIN sim_card c ON c.iccid=q.iccid
            JOIN carrier_account a ON a.id=c.carrier_account_id
            JOIN sku s ON s.id=c.sku_id
            JOIN account u ON u.id=q.requested_by
            """+where+" ORDER BY q.updated_at DESC LIMIT ?",args);
    }

    @GetMapping("/erp/supplier/summary")
    Map<String,Object> summary() {
        var byStatus=new LinkedHashMap<String,Object>();
        for(var row:db.queryForList("SELECT last_status,COUNT(*) AS total FROM carrier_sync_queue GROUP BY last_status ORDER BY last_status"))
            byStatus.put(String.valueOf(row.get("last_status")),row.get("total"));
        long enabled=Optional.ofNullable(db.queryForObject(
            "SELECT COUNT(*) FROM carrier_account WHERE enabled=TRUE",Long.class)).orElse(0L);
        long cards=Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM sim_card",Long.class)).orElse(0L);
        long missing=Optional.ofNullable(db.queryForObject(
            "SELECT COUNT(*) FROM sim_card WHERE imsi IS NULL OR msisdn IS NULL",Long.class)).orElse(0L);
        return Map.of("byStatus",byStatus,"enabledAccounts",enabled,"cards",cards,"missingIdentity",missing);
    }

    @PostMapping("/erp/cards/{iccid}/retry")
    void retry(@PathVariable String iccid) {
        int changed=db.update("""
            UPDATE carrier_sync_queue SET attempts=0,next_attempt=CURRENT_TIMESTAMP,last_status='QUEUED',
            last_error=NULL,error_class=NULL,completed_at=NULL,lease_token=NULL,lease_until=NULL
            WHERE iccid=?
            """,iccid);
        Orders.require(changed==1,"没有可重试的供应商回填任务");
    }

    @PostMapping("/erp/supplier/retry-failed")
    Map<String,Integer> retryFailed() {
        int changed=db.update("""
            UPDATE carrier_sync_queue SET attempts=0,next_attempt=CURRENT_TIMESTAMP,last_status='QUEUED',
            last_error=NULL,error_class=NULL,completed_at=NULL,lease_token=NULL,lease_until=NULL
            WHERE last_status IN ('FAILED','PERMANENT_FAILED','RETRYABLE_FAILED')
            """);
        return Map.of("updated",changed);
    }

    /** 把缺少卡号/IMSI 或从未回填的卡重新排入队列，用于老数据补拉。 */
    @PostMapping("/erp/supplier/backfill")
    Map<String,Integer> backfill(Principal user) {
        String scope="""
            FROM sim_card c JOIN carrier_account a ON a.id=c.carrier_account_id
            WHERE a.enabled=TRUE AND (c.msisdn IS NULL OR c.imsi IS NULL OR c.last_reconciled_at IS NULL)
            """;
        int candidates=Optional.ofNullable(db.queryForObject("SELECT COUNT(*) "+scope,Integer.class)).orElse(0);
        int changed=db.update("""
            INSERT INTO carrier_sync_queue(iccid,requested_by,operation,request_id)
            SELECT c.iccid,?,'IDENTITY',UUID() """+scope+"""
            ON DUPLICATE KEY UPDATE
            attempts=0,next_attempt=CURRENT_TIMESTAMP,last_status='QUEUED',last_error=NULL,error_class=NULL,
            completed_at=NULL,lease_token=NULL,lease_until=NULL
            """,orders.actor(user));
        return Map.of("queued",candidates,"affected",changed);
    }
}
