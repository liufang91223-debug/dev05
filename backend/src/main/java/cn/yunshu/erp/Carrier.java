package cn.yunshu.erp;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Carrier {
    private final JdbcTemplate db;
    private final Orders orders;
    private final TransactionTemplate transactions;
    private final SupplierAccounts suppliers;
    private final SupplierRouter router;
    private final PlanCycles plans;
    private final Cards cards;
    private final ObjectMapper json;

    Carrier(JdbcTemplate db,Orders orders,TransactionTemplate transactions,SupplierAccounts suppliers,
            SupplierRouter router,PlanCycles plans,Cards cards,ObjectMapper json) {
        this.db=db; this.orders=orders; this.transactions=transactions;
        this.suppliers=suppliers; this.router=router; this.plans=plans; this.cards=cards; this.json=json;
    }

    record Account(String name,String credential_ref,boolean enabled,String supplier_type,String api_category,
                   String api_sub_category,String adapter_key,String api_url) {}

    @GetMapping("/admin/carriers") Object list() {
        return db.queryForList("""
            SELECT id,name,credential_ref,enabled,supplier_type,api_category,api_sub_category,adapter_key,api_url,config_json
            FROM carrier_account ORDER BY id
            """);
    }

    @PostMapping("/admin/carriers") void create(@RequestBody Account account) {
        Account normal=normalise(account);
        validate(normal);
        db.update("""
            INSERT INTO carrier_account(name,credential_ref,enabled,supplier_type,api_category,api_sub_category,adapter_key,api_url)
            VALUES(?,?,?,?,?,?,?,?)
            """,normal.name(),normal.credential_ref(),normal.enabled(),normal.supplier_type(),
            normal.api_category(),normal.api_sub_category(),normal.adapter_key(),normal.api_url());
    }

    @PutMapping("/admin/carriers/{id}") void edit(@PathVariable long id,@RequestBody Account account) {
        Account normal=normalise(account);
        validate(normal);
        orders.one("SELECT id FROM carrier_account WHERE id=?",id);
        db.update("""
            UPDATE carrier_account SET name=?,credential_ref=?,enabled=?,supplier_type=?,api_category=?,
            api_sub_category=?,adapter_key=?,api_url=? WHERE id=?
            """,normal.name(),normal.credential_ref(),normal.enabled(),normal.supplier_type(),
            normal.api_category(),normal.api_sub_category(),normal.adapter_key(),normal.api_url(),id);
    }

    private Account normalise(Account account) {
        return new Account(account.name(),account.credential_ref(),account.enabled(),
            blank(account.supplier_type(),"CMCC").toUpperCase(java.util.Locale.ROOT),
            blank(account.api_category(),"CMCC").toUpperCase(java.util.Locale.ROOT),
            blank(account.api_sub_category(),"CMCC_CTBOSS").toUpperCase(java.util.Locale.ROOT),
            blank(account.adapter_key(),"cmcc-ctboss").toLowerCase(java.util.Locale.ROOT),
            blank(account.api_url(),null));
    }

    private void validate(Account account) {
        Orders.require(account.name()!=null && !account.name().isBlank() && account.name().length()<=120
            && account.credential_ref()!=null && account.credential_ref().matches("[A-Z0-9_]{1,40}"),
            "填写账号名称和大写凭据标识");
        Orders.require(account.supplier_type()!=null && account.supplier_type().matches("[A-Z0-9_]{1,20}"),
            "供应商类型只能使用大写字母、数字和下划线");
        Orders.require(account.adapter_key()!=null && account.adapter_key().matches("[a-z0-9-]{1,40}"),
            "适配器标识只能使用小写字母、数字和短横线");
        Orders.require(account.api_sub_category()!=null && account.api_sub_category().matches("[A-Z0-9_]{1,30}"),
            "接口子类格式不正确");
        Orders.require(account.api_url()==null || account.api_url().matches("https?://[^\\s]{1,240}"),
            "供应商接口地址必须是 http(s) 地址");
    }

    private static String blank(String value,String fallback) {
        return value==null || value.isBlank()?fallback:value.strip();
    }

    @PostMapping("/admin/carriers/{id}/test") void test(@PathVariable long id) {
        SupplierAccount account=suppliers.byId(id);
        CardInfoProvider provider=router.forAccount(account);
        provider.open(account);
    }

    @GetMapping("/erp/cards/{iccid}/snapshots") Object snapshots(@PathVariable String iccid) {
        return db.queryForList("SELECT id,fetched_at,period_month,usage_kb,offerings_json,unavailable FROM carrier_snapshot WHERE iccid=? ORDER BY id DESC LIMIT 10",iccid);
    }

    @PostMapping("/erp/cards/{iccid}/reconcile")
    void reconcile(@PathVariable String iccid,Principal user) {
        reconcileCard(iccid,orders.actor(user));
    }

    /** 查询一张卡并回填供应商数据。返回未能取得的可选字段数量，供队列标记 PARTIAL。 */
    int reconcileCard(String iccid,long actor) {
        var context=suppliers.card(iccid);
        Orders.require(context.enabled(),"请启用该卡的供应商账号");
        CardInfoProvider provider=router.forAccount(context.account());
        CardInfoProvider.Session session=provider.open(context.account());
        CardInfoResult info=provider.fetch(session,context.account(),iccid);
        var unavailable=new ArrayList<>(info.missing());
        String msisdn=info.msisdn();
        JsonNode offerings=info.offerings()==null?json.createArrayNode():info.offerings();
        var names=new LinkedHashSet<String>();
        for(var item:offerings)
            if(!item.path("offeringName").asText().isBlank())
                names.add(item.path("offeringName").asText());
        boolean terminal="TERMINAL".equals(context.kind());
        JsonNode usage=terminal
            ? provider.optional(session,"query/sim-data-usage-inpool",Map.of("token",session.token(),"iccid",iccid),
                unavailable,"流量池用量").path(0).path("apnList")
            : provider.optional(session,"query/sim-data-margin",Map.of("token",session.token(),"iccid",iccid),
                unavailable,"套餐用量").path(0).path("accmMarginList");
        BigDecimal used=sum(usage,terminal?"apnUseAmount":"useAmount",terminal?1024:1);
        BigDecimal quota=terminal?null:sum(usage,"totalAmount",1);
        LocalDate month=Cards.today().withDayOfMonth(1);
        transactions.executeWithoutResult(status -> {
            var locked=orders.one("SELECT carrier_account_id FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
            if(Orders.number(locked,"carrier_account_id")!=context.account().id())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"卡所属账号已变化，请重新查询");
            db.update("""
                UPDATE sim_card SET msisdn=COALESCE(?,msisdn),imsi=COALESCE(?,imsi),imei=COALESCE(?,imei),
                activated_on=COALESCE(?,activated_on),carrier_expiry=COALESCE(?,carrier_expiry),
                last_reconciled_at=CURRENT_TIMESTAMP WHERE iccid=?
                """,msisdn,info.imsi(),info.imei(),info.activatedOn(),info.carrierExpiry(),iccid);
            db.update("""
                INSERT INTO carrier_snapshot(iccid,period_month,usage_kb,offerings_json,unavailable)
                VALUES(?,?,?,?,?)
                """,iccid,month,used,offerings.isArray()?offerings.toString():"[]",String.join("、",unavailable));
            String plan=String.join("、",names);
            if(plan.length()>500)plan=plan.substring(0,500);
            db.update("""
                INSERT INTO carrier_services(iccid,plan_name,apn_details,communication_details) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE plan_name=CASE WHEN ?='' THEN plan_name ELSE ? END,
                apn_details=COALESCE(?,apn_details),communication_details=COALESCE(?,communication_details)
                """,iccid,plan,info.apn()==null||info.apn().isEmpty()?null:info.apn().toString(),
                info.services()==null||info.services().isEmpty()?null:info.services().toString(),
                plan,plan,info.apn()==null||info.apn().isEmpty()?null:info.apn().toString(),
                info.services()==null||info.services().isEmpty()?null:info.services().toString());
            if(terminal && used!=null)plans.applyPoolReading(iccid,Cards.today(),used);
            if(!terminal && used!=null && quota!=null) {
                db.update("""
                    INSERT INTO group_usage(iccid,period_month,quota_kb,used_kb)
                    VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE quota_kb=?,used_kb=?,fetched_at=CURRENT_TIMESTAMP
                    """,iccid,month,quota,used,quota,used);
            }
            db.update("INSERT INTO sim_event(iccid,actor_id,action,detail) VALUES(?,?,'RECONCILE',?)",
                iccid,actor,unavailable.isEmpty()?"供应商回填完成":"未返回："+String.join("、",unavailable));
        });
        return unavailable.size();
    }

    @GetMapping("/erp/cards/{iccid}/services") Object services(@PathVariable String iccid) {
        return db.queryForList("SELECT plan_name,apn_details,communication_details FROM carrier_services WHERE iccid=?",iccid);
    }

    record Baseline(LocalDate startsOn,BigDecimal rawKb,BigDecimal carryKb) {}
    @PutMapping("/erp/cards/{iccid}/baseline")
    @org.springframework.transaction.annotation.Transactional
    void baseline(@PathVariable String iccid,@RequestBody Baseline b,Principal user) {
        cards.requireBuyer(iccid,user);
        plans.anchor(iccid,b.startsOn(),b.rawKb(),b.carryKb());
        cards.event(iccid,orders.actor(user),"USAGE_BASELINE",b.startsOn()+" / "+b.rawKb()+" KB + "+b.carryKb()+" KB");
    }

    record ConfirmedUsage(LocalDate startsOn,long usageKb) {}
    @PutMapping("/erp/cards/{iccid}/confirmed-usage")
    @org.springframework.transaction.annotation.Transactional
    void confirmedUsage(@PathVariable String iccid,@RequestBody ConfirmedUsage body,Principal user) {
        cards.override(iccid,new Cards.Usage(body.startsOn(),body.usageKb(),"采购核对录入"),user);
    }

    private BigDecimal sum(JsonNode array,String key,int divisor) {
        if(!array.isArray() || array.isEmpty())return null;
        BigDecimal total=BigDecimal.ZERO;
        try {
            for(var row:array) {
                var value=new BigDecimal(row.path(key).asText());
                if(value.signum()<0)return null;
                total=total.add(value);
            }
            return total.divide(BigDecimal.valueOf(divisor),3,RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) { return null; }
    }
}
