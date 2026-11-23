package cn.yunshu.erp;

import java.security.Principal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Service
class CarrierSync {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Carrier carrier;
    CarrierSync(JdbcTemplate db,TransactionTemplate transactions,Carrier carrier) {
        this.db=db;
        this.transactions=transactions;
        this.carrier=carrier;
    }

    record Job(String iccid,String actor,String token,int attempts) {}

    @Scheduled(fixedDelayString="${erp.carrier.poll-ms:3000}")
    void poll() {
        Job job=transactions.execute(tx->{
            var rows=db.queryForList("""
                SELECT q.iccid,q.attempts,u.username FROM carrier_sync_queue q
                JOIN account u ON u.id=q.requested_by JOIN sim_card c ON c.iccid=q.iccid
                JOIN carrier_account a ON a.id=c.carrier_account_id
                WHERE a.enabled=TRUE AND q.next_attempt<=CURRENT_TIMESTAMP
                ORDER BY q.next_attempt LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();
            String token=UUID.randomUUID().toString();
            var claimed=new Job((String)row.get("iccid"),(String)row.get("username"),token,
                ((Number)row.get("attempts")).intValue()+1);
            db.update("""
                UPDATE carrier_sync_queue SET lease_token=?, attempts=?,last_status='RUNNING',
                next_attempt=TIMESTAMPADD(MINUTE,10,CURRENT_TIMESTAMP) WHERE iccid=?
                """,token,claimed.attempts(),claimed.iccid());
            return claimed;
        });
        if(job==null)return;
        try{
            carrier.reconcile(job.iccid(),job::actor);
            db.update("UPDATE carrier_sync_queue SET lease_token=NULL,next_attempt=NULL,last_status='DONE' WHERE iccid=? AND lease_token=?",job.iccid(),job.token());
        }catch(RuntimeException e){
            db.update("""
                UPDATE carrier_sync_queue SET lease_token=NULL, last_status='FAILED',
                next_attempt=CASE WHEN attempts<3 THEN TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) ELSE NULL END
                WHERE iccid=? AND lease_token=?
                """,job.iccid(),job.token());
        }
    }
}

@RestController
@RequestMapping("/api/portal/cards")
class PortalRefresh {
    private final JdbcTemplate db;
    private final Orders orders;
    PortalRefresh(JdbcTemplate db,Orders orders) {this.db=db;this.orders=orders;}
    @PostMapping("/refresh")
    void refresh(Principal user) {
        db.update("""
            INSERT INTO carrier_sync_queue(iccid,requested_by)
            SELECT c.iccid,? FROM sim_card c JOIN carrier_account a ON a.id=c.carrier_account_id
            WHERE c.customer_id=? AND a.enabled=TRUE
            ON DUPLICATE KEY UPDATE
            attempts=IF(next_attempt IS NULL AND requested_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP),0,attempts),
            next_attempt=IF(next_attempt IS NULL AND requested_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,next_attempt),
            requested_at=IF(next_attempt<=CURRENT_TIMESTAMP AND attempts=0,CURRENT_TIMESTAMP,requested_at)
            """,orders.actor(user),orders.customer(user));
    }
}
