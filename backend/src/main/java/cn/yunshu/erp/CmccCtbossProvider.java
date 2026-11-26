package cn.yunshu.erp;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** 中国移动 OneLink 集团 API（CTBOSS/EC v5）适配器。 */
@Component
class CmccCtbossProvider implements CardInfoProvider {
    static final String KEY="cmcc-ctboss";
    private static final String DEFAULT_BASE="https://api.iot.10086.cn";

    private final CarrierHttp http;
    private final ObjectMapper json;
    private final SupplierCredentials credentials;
    /** 短期令牌缓存：大批量入库时避免每张卡都请求一次 token。 */
    private static final Duration TOKEN_TTL=Duration.ofSeconds(60);
    private final Map<Long,CachedToken> tokens=new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedToken(String token,Instant expiresAt) {}

    CmccCtbossProvider(CarrierHttp http,ObjectMapper json,SupplierCredentials credentials) {
        this.http=http; this.json=json; this.credentials=credentials;
    }

    @Override public String adapterKey() { return KEY; }

    @Override public Session open(SupplierAccount account) {
        var cached=tokens.get(account.id());
        if(cached!=null && cached.expiresAt().isAfter(Instant.now()))
            return new Session(account,cached.token());
        var creds=credentials.load(account);
        JsonNode result=execute(account,"get/token",Map.of(
            "appid",creds.appId(),"password",creds.password(),"refresh","0"));
        String token=result.path(0).path("token").asText();
        if(token.isBlank())throw SupplierFailure.retryable("CMCC_TOKEN_EMPTY","运营商没有返回令牌");
        tokens.put(account.id(),new CachedToken(token,Instant.now().plus(TOKEN_TTL)));
        return new Session(account,token);
    }

    @Override public CardInfoResult fetch(Session session,SupplierAccount account,String iccid) {
        var missing=new ArrayList<String>();
        var query=Map.of("token",session.token(),"iccid",iccid);
        var basicResponse=execute(account,"query/sim-basic-info",query);
        if(!basicResponse.isArray() || basicResponse.isEmpty())
            throw SupplierFailure.permanent("SUPPLIER_CARD_NOT_FOUND","运营商未返回该 ICCID 的基础信息");
        var basic=basicResponse.path(0);
        String returned=basic.path("iccid").asText("").strip();
        if(!iccid.equalsIgnoreCase(returned))
            throw SupplierFailure.permanent("SUPPLIER_ICCID_MISMATCH","运营商返回的 ICCID 与本卡不符");
        String msisdn=text(basic,"msisdn",24);
        String imsi=text(basic,"imsi",24);
        if(msisdn==null)missing.add("卡号 MSISDN");
        if(imsi==null)missing.add("IMSI");
        LocalDate activated=parseDate(basic.path("activeDate").asText());
        String imei=text(optional(session,"query/sim-terminal-device-info",query,missing,"设备 IMEI")
            .path(0),"terminalDeviceImei",24);
        JsonNode offerings=msisdn==null?json.createArrayNode()
            :optional(session,"query/ordered-offerings",Map.of("token",session.token(),"msisdn",msisdn,"queryType","3"),
                missing,"资费").path(0).path("offeringInfoList");
        JsonNode apn=optional(session,"query/apn-info",query,missing,"APN");
        JsonNode services=optional(session,"query/sim-activated-features-info",query,missing,"通信功能");
        LocalDate carrierExpiry=null;
        var endDates=new HashSet<LocalDate>();
        for(var item:offerings) {
            LocalDate date=parseDate(item.path("expiriedDate").asText());
            if(date!=null)endDates.add(date);
        }
        if(endDates.size()==1)carrierExpiry=endDates.iterator().next();
        return new CardInfoResult(iccid,msisdn,imsi,imei,activated,carrierExpiry,List.copyOf(missing),
            offerings,apn,services,transId(account),Instant.now());
    }

    @Override public JsonNode optional(Session session,String endpoint,Map<String,String> query,
                                      List<String> missing,String label) {
        try { return execute(session.account(),endpoint,query); }
        catch (SupplierFailure failure) {
            if(failure.kind()==SupplierFailure.Kind.CONFIG)throw failure;
            missing.add(label);
            return json.createArrayNode();
        }
    }

    @Override public JsonNode call(Session session,String endpoint,Map<String,String> query) {
        return execute(session.account(),endpoint,query);
    }

    private JsonNode execute(SupplierAccount account,String endpoint,Map<String,String> query) {
        var creds=credentials.load(account);
        String transid=transId(account);
        var url=new StringBuilder(base(account)).append("/v5/ec/").append(endpoint)
            .append("?transid=").append(transid);
        query.forEach((key,value)->url.append('&').append(key).append('=')
            .append(URLEncoder.encode(value,StandardCharsets.UTF_8)));
        CarrierHttp.Response response;
        try {
            response=http.get(URI.create(url.toString()));
        } catch (java.io.IOException ex) {
            throw SupplierFailure.retryable("SUPPLIER_HTTP_IO","供应商连接超时或网络异常");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw SupplierFailure.retryable("SUPPLIER_INTERRUPTED","供应商请求被中断");
        }
        if(response.status()!=200) {
            var kind=response.status()==400||response.status()==401||response.status()==403
                ||response.status()==404 ? SupplierFailure.Kind.PERMANENT : SupplierFailure.Kind.RETRYABLE;
            throw new SupplierFailure(kind,"SUPPLIER_HTTP_"+response.status(),
                "供应商 HTTP "+response.status());
        }
        try {
            JsonNode root=json.readTree(response.body());
            String status=root.path("status").asText();
            if(!"0".equals(status)) {
                String message=root.path("message").asText("");
                if(message.isBlank())message=root.path("desc").asText("");
                if(message.isBlank())message="供应商返回状态 "+(status.matches("[0-9]{1,8}")?status:"未知");
                throw SupplierFailure.retryable("CMCC_STATUS_"+(status.isBlank()?"UNKNOWN":status),
                    safe(message,180));
            }
            return root.path("result");
        } catch (java.io.IOException ex) {
            throw SupplierFailure.retryable("SUPPLIER_BAD_JSON","供应商返回格式异常");
        }
    }

    private String base(SupplierAccount account) {
        String url=account.apiUrl();
        if(url==null || url.isBlank())return DEFAULT_BASE;
        return url.endsWith("/")?url.substring(0,url.length()-1):url;
    }

    private String transId(SupplierAccount account) {
        String appId=credentials.load(account).appId();
        String timestamp=LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return appId+timestamp+String.format(Locale.ROOT,"%08d",ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String text(JsonNode node,String field,int max) {
        String value=node.path(field).asText("").strip();
        return value.isEmpty()||value.length()>max?null:value;
    }
    private static LocalDate parseDate(String value) {
        if(value==null || value.length()<10)return null;
        try { return LocalDate.parse(value.substring(0,10)); }
        catch (java.time.format.DateTimeParseException ex) { return null; }
    }
    private static String safe(String value,int max) {
        String clean=value.replaceAll("[\\r\\n\\t]"," ").strip();
        return clean.length()<=max?clean:clean.substring(0,max);
    }
}
