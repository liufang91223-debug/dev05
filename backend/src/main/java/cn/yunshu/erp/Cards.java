package cn.yunshu.erp;

import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static cn.yunshu.erp.Orders.*;

@RestController
@RequestMapping("/api")
class Cards {
    private final JdbcTemplate db;
    private final Orders orders;
    private final PlanCycles plans;
    Cards(JdbcTemplate db, Orders orders,PlanCycles plans) { this.db=db; this.orders=orders; this.plans=plans; }

    record CardItem(String iccid,long carrier,LocalDate activatedOn,Long quotaKb,String planName,Integer cycleMonths) {}
    record Movement(long sku,List<CardItem> cards,LocalDate expiry) {}
    record Fulfillment(List<Movement> lines) {}

    @Transactional
    void fulfill(@PathVariable long id,@RequestBody Fulfillment body,Principal user) {
        var order=orders.lockOrder(id,"APPROVED");
        var expected=db.queryForList("SELECT l.*,s.kind FROM order_line l JOIN sku s ON s.id=l.sku_id WHERE order_id=? ORDER BY sku_id",id);
        var supplied=new HashMap<Long,Movement>();
        require(body.lines()!=null && body.lines().size()<=expected.size(),"出入库明细无效");
        for (var movement : body.lines())
            require(movement!=null && supplied.putIfAbsent(movement.sku(),movement)==null,"重复商品明细");
        boolean receipt=order.get("direction").equals("PURCHASE");
        long warehouse=orders.warehouse(id);
        for (var row : expected) {
            long sku=number(row,"sku_id"), qty=number(row,"quantity");
            var movement=supplied.remove(sku);
            if (!row.get("kind").equals("HARDWARE")) {
                require(movement!=null && movement.cards()!=null && movement.cards().size()==qty,"ICCID 数量与单据数量不一致");
                var unique=new HashSet<String>();
                for (var card:movement.cards()) {
                    require(card!=null && card.iccid()!=null && card.iccid().matches("[0-9A-Z]{18,24}") && unique.add(card.iccid()),"ICCID 无效或重复");
                    if (receipt) {
                        orders.one("SELECT id FROM carrier_account WHERE id=?",card.carrier());
                        require(card.quotaKb()==null || card.quotaKb()>=0,"额度不能小于零");
                        db.update("INSERT INTO sim_card(iccid,sku_id,carrier_account_id,purchase_order_id,activated_on,default_quota_kb) VALUES(?,?,?,?,?,?)",
                            card.iccid(),sku,card.carrier(),id,card.activatedOn(),card.quotaKb());
                        db.update("INSERT INTO card_location VALUES(?,?)",card.iccid(),warehouse);
                        db.update("INSERT INTO carrier_sync_queue(iccid,requested_by) VALUES(?,?)",card.iccid(),orders.actor(user));
                        int months=card.cycleMonths()==null?1:card.cycleMonths();
                        require(months>=1 && months<=24,"套餐周期为 1 至 24 个月");
                        db.update("INSERT INTO sim_plan VALUES(?,?,?)",card.iccid(),clean(card.planName(),100),months);
                    } else {
                        orders.one("SELECT iccid FROM card_location WHERE iccid=? AND warehouse_id=?",card.iccid(),warehouse);
                        require(movement.expiry()!=null && !movement.expiry().isBefore(today()),"填写有效的 ERP 到期日");
                        require(db.update("""
                            UPDATE sim_card SET customer_id=?,sale_order_id=?,delivered_on=?,erp_expiry=?
                            WHERE iccid=? AND sku_id=? AND customer_id IS NULL
                            """,number(order,"party_id"),id,today(),movement.expiry(),card.iccid(),sku)==1,"卡不在可选库存中");
                    }
                    if(!receipt) plans.ensure(card.iccid(),today());
                    event(card.iccid(),orders.actor(user),receipt?"RECEIVE":"DELIVER",String.valueOf(id));
                }
            }
            if (receipt) db.update("INSERT INTO warehouse_stock(warehouse_id,sku_id,on_hand) VALUES(?,?,?) ON DUPLICATE KEY UPDATE on_hand=on_hand+?",warehouse,sku,qty,qty);
            else require(db.update("UPDATE warehouse_stock SET on_hand=on_hand-?,reserved=reserved-? WHERE warehouse_id=? AND sku_id=? AND reserved>=?",qty,qty,warehouse,sku,qty)==1,"仓库锁定数量不足");
            if (receipt) db.update("INSERT INTO inventory(sku_id,on_hand) VALUES(?,?) ON DUPLICATE KEY UPDATE on_hand=on_hand+?",sku,qty,qty);
            else require(db.update("UPDATE inventory SET on_hand=on_hand-?,reserved=reserved-? WHERE sku_id=? AND reserved>=?",qty,qty,sku,qty)==1,"锁定库存不足");
        }
        require(supplied.isEmpty(),"存在不属于单据的商品");
        db.update("UPDATE erp_order SET state='FULFILLED' WHERE id=?",id);
    }

    @GetMapping("/erp/cards") List<Map<String,Object>> cards() {
        return db.queryForList("""
            SELECT c.*,s.kind,p.name AS customer_name,a.name AS carrier_name,cl.warehouse_id,pl.plan_name,pl.cycle_months FROM sim_card c
            JOIN sku s ON s.id=c.sku_id JOIN carrier_account a ON a.id=c.carrier_account_id
            LEFT JOIN sim_plan pl ON pl.iccid=c.iccid LEFT JOIN card_location cl ON cl.iccid=c.iccid LEFT JOIN party p ON p.id=c.customer_id ORDER BY c.iccid
            """);
    }
    record CardSettings(long carrier,Long quotaKb,String planName,Integer cycleMonths) {}
    @PutMapping("/erp/cards/{iccid}/settings") @Transactional
    void settings(@PathVariable String iccid,@RequestBody CardSettings body,Principal user) {
        requireBuyer(iccid,user);
        var card=orders.one("SELECT carrier_account_id FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
        orders.one("SELECT id FROM carrier_account WHERE id=?",body.carrier());
        require(body.quotaKb()==null || body.quotaKb()>=0,"额度不能小于零");
        boolean changed=number(card,"carrier_account_id")!=body.carrier();
        db.update("UPDATE sim_card SET carrier_account_id=?,default_quota_kb=? WHERE iccid=?",body.carrier(),body.quotaKb(),iccid);
        var months=body.cycleMonths()==null?1:body.cycleMonths();
        require(months>=1 && months<=24,"套餐周期须为 1 至 24 个月");
        var plan=clean(body.planName(),100);
        db.update("INSERT INTO sim_plan VALUES(?,?,?) ON DUPLICATE KEY UPDATE plan_name=?,cycle_months=?",iccid,plan,months,plan,months);
        if (changed) {
            db.update("DELETE FROM carrier_services WHERE iccid=?",iccid);
            db.update("DELETE FROM cycle_meter WHERE iccid=?",iccid);
            db.update("UPDATE usage_cycle SET carrier_kb=NULL,carrier_updated_at=NULL WHERE iccid=?",iccid);
            db.update("UPDATE sim_card SET msisdn=NULL,imei=NULL,carrier_expiry=NULL WHERE iccid=?",iccid);
            db.update("DELETE FROM group_usage WHERE iccid=?",iccid);
        }
        event(iccid,orders.actor(user),"SETTINGS","移动账号 "+body.carrier()+" / 默认额度 "+body.quotaKb());
    }
    @GetMapping("/erp/cards/{iccid}/cycles") List<Map<String,Object>> cycles(@PathVariable String iccid) {
        return db.queryForList("SELECT * FROM usage_cycle WHERE iccid=? ORDER BY starts_on DESC LIMIT 36",iccid);
    }
    record Cycle(LocalDate startsOn,LocalDate endsOn,long quotaKb) {}
    @PostMapping("/erp/cards/{iccid}/cycles") @Transactional
    void cycle(@PathVariable String iccid,@RequestBody Cycle body,Principal user) {
        requireBuyer(iccid,user);
        var card=orders.one("SELECT * FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
        require(body.startsOn()!=null && body.endsOn()!=null && body.endsOn().isAfter(body.startsOn()) && body.quotaKb()>=0,"套餐周期无效");
        require(card.get("customer_id")!=null && card.get("activated_on")!=null,"卡须已交付并激活");
        LocalDate activated=date(card.get("activated_on")), delivered=date(card.get("delivered_on"));
        require(!body.startsOn().isBefore(activated) && !body.startsOn().isBefore(delivered),"周期起点不能早于激活或交付");
        require(Objects.equals(0L,db.queryForObject(
            "SELECT COUNT(*) FROM usage_cycle WHERE iccid=? AND starts_on<? AND ends_on>?",Long.class,iccid,body.endsOn(),body.startsOn())),
            "已有重叠套餐周期");
        db.update("INSERT INTO usage_cycle(iccid,starts_on,ends_on,quota_kb) VALUES(?,?,?,?)",iccid,body.startsOn(),body.endsOn(),body.quotaKb());
    }
    record Usage(LocalDate startsOn,Long kb,String reason) {}
    @PutMapping("/erp/cards/{iccid}/override") @Transactional
    void override(@PathVariable String iccid,@RequestBody Usage body,Principal user) {
        requireBuyer(iccid,user);
        require(body.startsOn()!=null && (body.kb()==null || (body.kb()>=0 && body.reason()!=null
            && !body.reason().isBlank() && body.reason().length()<=300)),"填写非负用量和修正原因");
        long actor=orders.actor(user);
        require(db.update("""
            UPDATE usage_cycle SET override_kb=?,override_reason=?,override_by=?,override_updated_at=CURRENT_TIMESTAMP
            WHERE iccid=? AND starts_on=?
            """,body.kb(),body.kb()==null?null:body.reason(),actor,iccid,body.startsOn())==1,"套餐周期不存在");
        event(iccid,actor,"OVERRIDE",body.kb()==null?"撤销修正":body.kb()+" KB / "+body.startsOn());
    }
    record Expiry(LocalDate expiresOn) {}
    @PutMapping("/erp/cards/{iccid}/expiry") @Transactional
    void expiry(@PathVariable String iccid,@RequestBody Expiry body,Principal user) {
        var card=orders.one("SELECT * FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
        require(card.get("customer_id")!=null && body.expiresOn()!=null
            && !body.expiresOn().isBefore(today())
            && !body.expiresOn().isBefore(date(card.get("erp_expiry"))),"填写不早于现有有效期的日期");
        db.update("UPDATE sim_card SET erp_expiry=? WHERE iccid=?",body.expiresOn(),iccid);
        event(iccid,orders.actor(user),"RENEW",body.expiresOn().toString());
    }

    @Transactional
    void returnCard(@PathVariable String iccid,Principal user) {
        var card=orders.one("SELECT * FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
        require(card.get("customer_id")!=null,"该卡已退回或未销售");
        release(iccid,card,orders.actor(user),"RETURN");
        db.update("UPDATE inventory SET on_hand=on_hand+1 WHERE sku_id=?",number(card,"sku_id"));
        db.update("UPDATE warehouse_stock SET on_hand=on_hand+1 WHERE sku_id=? AND warehouse_id=(SELECT warehouse_id FROM card_location WHERE iccid=?)",number(card,"sku_id"),iccid);
    }

    record Exchange(String replacementIccid) {}
    @Transactional
    void exchange(@PathVariable String iccid,@RequestBody Exchange body,Principal user) {
        String replacement=body.replacementIccid();
        require(replacement!=null && replacement.matches("[0-9A-Z]{18,24}") && !iccid.equalsIgnoreCase(replacement),"新卡 ICCID 无效");
        var locked=db.queryForList("SELECT * FROM sim_card WHERE iccid IN (?,?) ORDER BY iccid FOR UPDATE",iccid,replacement);
        require(locked.size()==2,"找不到新卡或旧卡");
        var old=locked.stream().filter(row -> iccid.equalsIgnoreCase(row.get("iccid").toString())).findFirst().orElseThrow();
        var next=locked.stream().filter(row -> replacement.equalsIgnoreCase(row.get("iccid").toString())).findFirst().orElseThrow();
        require(old.get("customer_id")!=null && next.get("customer_id")==null
            && number(old,"sku_id")==number(next,"sku_id"),"新卡须为同商品的在库卡，旧卡须已销售");
        long actor=orders.actor(user);
        var oldLocation=orders.one("SELECT warehouse_id FROM card_location WHERE iccid=?",iccid);
        var newLocation=orders.one("SELECT warehouse_id FROM card_location WHERE iccid=?",replacement);
        require(number(oldLocation,"warehouse_id")==number(newLocation,"warehouse_id"),"换货新旧卡须属于同一仓库");
        release(iccid,old,actor,"EXCHANGE_OUT");
        db.update("UPDATE sim_card SET customer_id=?,sale_order_id=?,delivered_on=?,erp_expiry=? WHERE iccid=?",
            old.get("customer_id"),old.get("sale_order_id"),today(),old.get("erp_expiry"),replacement);
        event(replacement,actor,"EXCHANGE_IN","被换 ICCID "+iccid);
    }
    private void release(String iccid,Map<String,Object> card,long actor,String action) {
        db.update("DELETE FROM usage_cycle WHERE iccid=? AND starts_on>=?",iccid,today());
        db.update("UPDATE usage_cycle SET ends_on=? WHERE iccid=? AND starts_on<? AND ends_on>?",today(),iccid,today(),today());
        db.update("UPDATE sim_card SET customer_id=NULL,sale_order_id=NULL,delivered_on=NULL,erp_expiry=NULL WHERE iccid=?",iccid);
        event(iccid,actor,action,"原销售单 "+card.get("sale_order_id"));
    }

    @GetMapping("/portal/cards") @Transactional List<Map<String,Object>> customerCards(Principal user) {
        long customer=orders.customer(user);
        for(String iccid:db.queryForList("""
            SELECT c.iccid FROM sim_card c JOIN sku s ON s.id=c.sku_id
            WHERE c.customer_id=? AND s.kind='TERMINAL' AND c.activated_on IS NOT NULL
            AND c.default_quota_kb IS NOT NULL AND c.erp_expiry>=?
            AND NOT EXISTS(SELECT 1 FROM usage_cycle u WHERE u.iccid=c.iccid AND u.starts_on<=? AND u.ends_on>?)
            ORDER BY c.iccid
            """,String.class,customer,today(),today(),today())) plans.ensure(iccid,today());
        return orders.project("cards",db.queryForList("""
            SELECT card.*,GREATEST(0,quota_kb-used_kb) AS remaining_kb
            FROM (
                SELECT c.iccid,c.msisdn,c.imei,c.activated_on,c.erp_expiry,
                  CASE WHEN s.kind='GROUP' THEN svc.plan_name ELSE pl.plan_name END AS plan_name,
                  CASE WHEN s.kind='GROUP' THEN g.period_month ELSE u.starts_on END AS starts_on,
                  CASE WHEN s.kind='GROUP' THEN TIMESTAMPADD(MONTH,1,g.period_month) ELSE u.ends_on END AS ends_on,
                  CASE WHEN s.kind='GROUP' THEN g.quota_kb ELSE u.quota_kb END AS quota_kb,
                  CASE WHEN s.kind='GROUP' THEN g.used_kb ELSE COALESCE(u.override_kb,u.carrier_kb) END AS used_kb,
                  CASE WHEN s.kind='GROUP' THEN g.fetched_at
                       WHEN u.override_kb IS NOT NULL THEN u.override_updated_at ELSE u.carrier_updated_at END AS updated_at
                FROM sim_card c JOIN sku s ON s.id=c.sku_id
                LEFT JOIN usage_cycle u ON c.iccid=u.iccid AND u.starts_on<=? AND u.ends_on>?
                LEFT JOIN sim_plan pl ON pl.iccid=c.iccid LEFT JOIN carrier_services svc ON svc.iccid=c.iccid
                LEFT JOIN group_usage g ON g.iccid=c.iccid AND g.period_month=?
                WHERE c.customer_id=? ORDER BY c.iccid
            ) card ORDER BY card.iccid
            """,today(),today(),today().withDayOfMonth(1),orders.customer(user)));
    }
    void requireBuyer(String iccid,Principal user) {
        var account=orders.me(user);
        if("ADMIN".equals(account.get("role")))return;
        var linked=db.queryForList("""
            SELECT c.iccid FROM sim_card c JOIN erp_order o ON o.id=c.purchase_order_id LEFT JOIN order_header h ON h.order_id=o.id
            WHERE c.iccid=? AND (o.creator_id=? OR h.manager=?)
            """,iccid,number(account,"id"),user.getName());
        if(linked.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"仅关联采购人员或管理员可维护卡额度和用量");
    }
    void event(String iccid,long user,String action,String detail) {
        db.update("INSERT INTO sim_event(iccid,actor_id,action,detail) VALUES(?,?,?,?)",iccid,user,action,detail);
    }
    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    static LocalDate date(Object value) { return LocalDate.parse(value.toString()); }
}
