package cn.yunshu.erp;

import java.security.Principal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 供应商回填队列。外部 HTTP 调用始终在数据库事务之外执行。 */
@Service
class CarrierSync {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Carrier carrier;
    private final int maxAttempts;
    private final int leaseMinutes;

    CarrierSync(JdbcTemplate db,TransactionTemplate transactions,Carrier carrier,
                @Value("${erp.carrier.max-attempts:5}") int maxAttempts,
                @Value("${erp.carrier.lease-minutes:10}") int leaseMinutes) {
        this.db=db; this.transactions=transactions; this.carrier=carrier;
        this.maxAttempts=Math.max(1,maxAttempts); this.leaseMinutes=Math.max(1,leaseMinutes);
    }

    record Job(String iccid,long actor,String token,int attempts,String operation) {}

    @Scheduled(fixedDelayString="${erp.carrier.poll-ms:3000}")
    void poll() {
        Job job=transactions.execute(tx -> {
            var rows=db.queryForList("""
                SELECT q.iccid,q.requested_by,q.attempts,q.operation
                FROM carrier_sync_queue q
                JOIN sim_card c ON c.iccid=q.iccid
                JOIN carrier_account a ON a.id=c.carrier_account_id
                WHERE a.enabled=TRUE AND q.next_attempt<=CURRENT_TIMESTAMP
                  AND (q.lease_until IS NULL OR q.lease_until<CURRENT_TIMESTAMP)
                ORDER BY q.next_attempt,q.iccid LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();
            String token=UUID.randomUUID().toString();
            var claimed=new Job((String)row.get("iccid"),((Number)row.get("requested_by")).longValue(),token,
                ((Number)row.get("attempts")).intValue()+1,String.valueOf(row.get("operation")));
            db.update("""
                UPDATE carrier_sync_queue SET lease_token=?,lease_until=TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP),
                attempts=?,last_status='RUNNING',last_error=NULL,error_class=NULL,completed_at=NULL
                WHERE iccid=?
                """,token,leaseMinutes,claimed.attempts(),claimed.iccid());
            return claimed;
        });
        if(job==null)return;
        if(!"IDENTITY".equals(job.operation())) {
            finish(job,"PERMANENT_FAILED","UNSUPPORTED_OPERATION","不支持的回填操作 "+job.operation(),true);
            return;
        }
        try {
            int missing=carrier.reconcileCard(job.iccid(),job.actor());
            finish(job,missing>0?"PARTIAL":"SUCCEEDED",null,null,true);
        } catch (RuntimeException error) {
            SupplierFailure.Kind kind=classify(error);
            String message=message(error);
            boolean noMoreAttempts=job.attempts()>=maxAttempts;
            if(kind==SupplierFailure.Kind.PERMANENT || kind==SupplierFailure.Kind.CONFIG || noMoreAttempts) {
                String status=kind==SupplierFailure.Kind.RETRYABLE?"FAILED":"PERMANENT_FAILED";
                finish(job,status,kind.name(),message,true);
            } else {
                db.update("""
                    UPDATE carrier_sync_queue SET lease_token=NULL,lease_until=NULL,last_status='RETRYABLE_FAILED',
                    last_error=?,error_class=?,next_attempt=TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP)
                    WHERE iccid=? AND lease_token=?
                    """,message,kind.name(),backoffMinutes(job.attempts()),job.iccid(),job.token());
            }
        }
    }

    private void finish(Job job,String status,String errorClass,String message,boolean completed) {
        db.update("""
            UPDATE carrier_sync_queue SET lease_token=NULL,lease_until=NULL,last_status=?,last_error=?,error_class=?,
            completed_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE completed_at END,
            next_attempt=CASE WHEN ? THEN NULL ELSE next_attempt END
            WHERE iccid=? AND lease_token=?
            """,status,message,errorClass,completed,completed,job.iccid(),job.token());
    }

    /** 可重试：网络、超时、5xx、限流；永久失败：ICCID 不符、账号停用、参数错误。 */
    static SupplierFailure.Kind classify(Throwable error) {
        if(error instanceof SupplierFailure failure)return failure.kind();
        if(error instanceof ResponseStatusException status) {
            int code=status.getStatusCode().value();
            if(code==400||code==401||code==403||code==404||code==409||code==422)
                return SupplierFailure.Kind.PERMANENT;
        }
        return SupplierFailure.Kind.RETRYABLE;
    }

    static int backoffMinutes(int attempts) {
        int step=Math.min(Math.max(attempts-1,0),4);
        return Math.min(60,5*(1<<step));
    }

    static String message(Throwable error) {
        String raw=error.getMessage();
        if(raw==null || raw.isBlank())raw=error.getClass().getSimpleName();
        String clean=raw.replaceAll("[\\r\\n\\t]"," ").strip();
        if(error instanceof SupplierFailure failure)clean=failure.code()+" "+clean;
        return clean.length()<=500?clean:clean.substring(0,500);
    }
}

@RestController
@RequestMapping("/api")
class PortalRefresh {
    private final JdbcTemplate db;
    private final Orders orders;
    PortalRefresh(JdbcTemplate db,Orders orders) {this.db=db;this.orders=orders;}

    @PostMapping("/portal/cards/refresh")
    void refresh(Principal user) {
        db.update("""
            INSERT INTO carrier_sync_queue(iccid,requested_by,operation,request_id)
            SELECT c.iccid,?,'IDENTITY',UUID() FROM sim_card c JOIN carrier_account a ON a.id=c.carrier_account_id
            WHERE c.customer_id=? AND a.enabled=TRUE
            ON DUPLICATE KEY UPDATE
            attempts=IF(next_attempt IS NULL,0,attempts),
            next_attempt=IF(next_attempt IS NULL,CURRENT_TIMESTAMP,next_attempt),
            requested_at=IF(next_attempt<=CURRENT_TIMESTAMP AND attempts=0,CURRENT_TIMESTAMP,requested_at)
            """,orders.actor(user),orders.customer(user));
    }
}
