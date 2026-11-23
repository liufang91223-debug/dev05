package cn.yunshu.erp;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final CarrierHttp http;

    private final PlanCycles plans;
    private final Cards cards;
    Carrier(JdbcTemplate db,Orders orders,ObjectMapper json,TransactionTemplate transactions,PlanCycles plans,Cards cards,CarrierHttp http) {
        this.http=http;
        this.plans=plans; this.cards=cards;
        this.db=db; this.orders=orders; this.json=json; this.transactions=transactions;
    }

    record Account(String name,String credential_ref,boolean enabled) {}
    @GetMapping("/admin/carriers") Object list() {
        return db.queryForList("SELECT * FROM carrier_account ORDER BY id");
    }
    @PostMapping("/admin/carriers") void create(@RequestBody Account account) {
        validate(account);
        db.update("INSERT INTO carrier_account(name,credential_ref,enabled) VALUES(?,?,?)",
            account.name(),account.credential_ref(),account.enabled());
    }
    @PutMapping("/admin/carriers/{id}") void edit(@PathVariable long id,@RequestBody Account account) {
        validate(account);
        orders.one("SELECT id FROM carrier_account WHERE id=?",id);
        db.update("UPDATE carrier_account SET name=?,credential_ref=?,enabled=? WHERE id=?",
            account.name(),account.credential_ref(),account.enabled(),id);
    }
    private void validate(Account account) {
        Orders.require(account.name()!=null && !account.name().isBlank() && account.name().length()<=120
            && account.credential_ref()!=null && account.credential_ref().matches("[A-Z0-9_]{1,40}"),"填写账号名称和大写凭据标识");
    }

    @PostMapping("/admin/carriers/{id}/test") void test(@PathVariable long id) {
        token(credentials(orders.one("SELECT credential_ref FROM carrier_account WHERE id=?",id).get("credential_ref").toString()));
    }
    @GetMapping("/erp/cards/{iccid}/snapshots") Object snapshots(@PathVariable String iccid) {
        return db.queryForList("SELECT id,fetched_at,period_month,usage_kb,offerings_json,unavailable FROM carrier_snapshot WHERE iccid=? ORDER BY id DESC LIMIT 10",iccid);
    }

    @PostMapping("/erp/cards/{iccid}/reconcile")
    void reconcile(@PathVariable String iccid,Principal user) {
        var card=orders.one("""
            SELECT c.carrier_account_id,a.credential_ref,a.enabled,s.kind
            FROM sim_card c JOIN carrier_account a ON a.id=c.carrier_account_id JOIN sku s ON s.id=c.sku_id
            WHERE c.iccid=?
            """,iccid);
        Orders.require(Boolean.TRUE.equals(card.get("enabled")) || "1".equals(String.valueOf(card.get("enabled"))),"请启用该卡的移动集团账号");
        var creds=credentials(card.get("credential_ref").toString());
        String token=token(creds);
        var query=Map.of("token",token,"iccid",iccid);
        var basic=call("query/sim-basic-info",creds,query).path(0);
        Orders.require(iccid.equalsIgnoreCase(basic.path("iccid").asText()),"运营商返回的 ICCID 与本卡不符");
        var unavailable=new ArrayList<String>();
        String msisdn=text(basic,"msisdn",24);
        LocalDate activation=parseDate(basic.path("activeDate").asText());
        String imei=text(optional("query/sim-terminal-device-info",creds,query,unavailable,"设备 IMEI").path(0),"terminalDeviceImei",24);
        JsonNode offerings=msisdn==null ? json.createArrayNode() : optional("query/ordered-offerings",creds,
            Map.of("token",token,"msisdn",msisdn,"queryType","3"),unavailable,"资费").path(0).path("offeringInfoList");
        var apn=optional("query/apn-info",creds,query,unavailable,"APN");
        var services=optional("query/sim-activated-features-info",creds,query,unavailable,"通信功能");
        var names=new LinkedHashSet<String>();
        var endDates=new HashSet<LocalDate>();
        for (var item:offerings) {
            if(!item.path("offeringName").asText().isBlank())names.add(item.path("offeringName").asText());
            var date=parseDate(item.path("expiriedDate").asText());
            if (date!=null) endDates.add(date);
        }
        LocalDate carrierExpiry=endDates.size()==1?endDates.iterator().next():null;
        boolean terminal="TERMINAL".equals(card.get("kind"));
        JsonNode usage=terminal ? optional("query/sim-data-usage-inpool",creds,query,unavailable,"流量池用量")
            .path(0).path("apnList") : optional("query/sim-data-margin",creds,query,unavailable,"套餐用量")
            .path(0).path("accmMarginList");
        BigDecimal used=sum(usage,terminal?"apnUseAmount":"useAmount",terminal?1024:1);
        BigDecimal quota=terminal ? null : sum(usage,"totalAmount",1);
        LocalDate month=Cards.today().withDayOfMonth(1);
        long actor=orders.actor(user);
        transactions.executeWithoutResult(status -> {
            var locked=orders.one("SELECT carrier_account_id FROM sim_card WHERE iccid=? FOR UPDATE",iccid);
            if (Orders.number(locked,"carrier_account_id")!=Orders.number(card,"carrier_account_id"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"卡所属账号已变化，请重新查询");
            db.update("""
                UPDATE sim_card SET msisdn=COALESCE(?,msisdn),imei=COALESCE(?,imei),
                activated_on=COALESCE(?,activated_on),carrier_expiry=COALESCE(?,carrier_expiry) WHERE iccid=?
                """,msisdn,imei,activation,carrierExpiry,iccid);
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
                """,iccid,plan,apn.isEmpty()?null:apn.toString(),services.isEmpty()?null:services.toString(),
                plan,plan,apn.isEmpty()?null:apn.toString(),services.isEmpty()?null:services.toString());
            if(terminal && used!=null)plans.applyPoolReading(iccid,Cards.today(),used);
            if (!terminal && used!=null && quota!=null) {
                db.update("""
                    INSERT INTO group_usage(iccid,period_month,quota_kb,used_kb)
                    VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE quota_kb=?,used_kb=?,fetched_at=CURRENT_TIMESTAMP
                    """,iccid,month,quota,used,quota,used);
            }
            db.update("INSERT INTO sim_event(iccid,actor_id,action,detail) VALUES(?,?,'RECONCILE',?)",
                iccid,actor,unavailable.isEmpty()?"中国移动查询完成":"未返回："+String.join("、",unavailable));
        });
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
        if (!array.isArray() || array.isEmpty()) return null;
        BigDecimal total=BigDecimal.ZERO;
        try {
            for (var row:array) {
                var value=new BigDecimal(row.path(key).asText());
                if (value.signum()<0) return null;
                total=total.add(value);
            }
            return total.divide(BigDecimal.valueOf(divisor),3,RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) { return null; }
    }
    private record Credentials(String appId,String password) {}
    private Credentials credentials(String alias) {
        String appId=System.getenv("CMCC_"+alias+"_APPID");
        String password=System.getenv("CMCC_"+alias+"_PASSWORD");
        Orders.require(appId!=null && appId.matches("[0-9]{1,30}") && password!=null && !password.isBlank(),"服务端缺少该账号的移动凭据");
        return new Credentials(appId,password);
    }
    private String token(Credentials c) {
        String token=call("get/token",c,Map.of("appid",c.appId(),"password",c.password(),"refresh","0")).path(0).path("token").asText();
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"运营商没有返回令牌");
        return token;
    }
    private JsonNode optional(String endpoint,Credentials creds,Map<String,String> query,List<String> unavailable,String label) {
        try { return call(endpoint,creds,query); }
        catch (ResponseStatusException ex) { unavailable.add(label); return json.createArrayNode(); }
    }
    private JsonNode call(String endpoint,Credentials creds,Map<String,String> query) {
        String timestamp=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        var url=new StringBuilder("https://api.iot.10086.cn/v5/ec/").append(endpoint)
            .append("?transid=").append(creds.appId()).append(timestamp)
            .append(String.format(Locale.ROOT,"%08d",ThreadLocalRandom.current().nextInt(100_000_000)));
        query.forEach((key,value) -> url.append('&').append(key).append('=').append(URLEncoder.encode(value,StandardCharsets.UTF_8)));
        try {
            var result=json.readTree(http.read(URI.create(url.toString())));
            String code=result.path("status").asText();
            if (!"0".equals(code)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "中国移动返回状态 "+(code.matches("[0-9]{1,8}")?code:"未知"));
            return result.path("result");
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"中国移动连接超时或返回格式异常");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"运营商请求中断");
        }
    }
    private static String text(JsonNode node,String field,int max) {
        String value=node.path(field).asText().strip();
        return value.isEmpty() || value.length()>max?null:value;
    }
    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value.substring(0,10)); }
        catch (IndexOutOfBoundsException | java.time.format.DateTimeParseException ex) { return null; }
    }
}
