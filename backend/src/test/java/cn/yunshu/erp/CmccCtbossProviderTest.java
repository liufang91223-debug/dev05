package cn.yunshu.erp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;

class CmccCtbossProviderTest {
    private final SupplierAccount account=new SupplierAccount(1,"移动测试","T1",true,
        "CMCC","CMCC","CMCC_CTBOSS","cmcc-ctboss","http://mock.local");

    private CmccCtbossProvider provider(Map<String,CarrierHttp.Response> responses) {
        var credentials=new SupplierCredentials(){
            @Override String read(String name) { return name.endsWith("_APPID")?"10000001":"secret"; }
        };
        var http=new CarrierHttp(){
            @Override Response get(URI uri) {
                String path=uri.getPath().replace("/v5/ec/","");
                Response response=responses.get(path);
                if(response==null)throw new AssertionError("unexpected endpoint "+path);
                return response;
            }
        };
        return new CmccCtbossProvider(http,new ObjectMapper(),credentials);
    }

    private Map<String,CarrierHttp.Response> happy() {
        return Map.of(
            "get/token",new CarrierHttp.Response(200,"{\"status\":\"0\",\"result\":[{\"token\":\"tk\"}]}"),
            "query/sim-basic-info",new CarrierHttp.Response(200,"""
                {"status":"0","result":[{"iccid":"89860000000000000001","msisdn":"8613800000000",
                "imsi":"460000000000001","activeDate":"2026-01-02 10:00:00"}]}
                """),
            "query/sim-terminal-device-info",new CarrierHttp.Response(200,
                "{\"status\":\"0\",\"result\":[{\"terminalDeviceImei\":\"860000000000001\"}]}"),
            "query/ordered-offerings",new CarrierHttp.Response(200,"""
                {"status":"0","result":[{"offeringInfoList":[{"offeringName":"测试套餐","expiriedDate":"2027-01-01"}]}]}
                """),
            "query/apn-info",new CarrierHttp.Response(200,"{\"status\":\"0\",\"result\":[]}"),
            "query/sim-activated-features-info",new CarrierHttp.Response(200,"{\"status\":\"0\",\"result\":[]}")
        );
    }

    @Test void readsImsiAndIdentityFields() {
        var provider=provider(happy());
        var session=provider.open(account);
        var info=provider.fetch(session,account,"89860000000000000001");
        assertEquals("8613800000000",info.msisdn());
        assertEquals("460000000000001",info.imsi());
        assertEquals("860000000000001",info.imei());
        assertEquals("2026-01-02",info.activatedOn().toString());
        assertEquals("2027-01-01",info.carrierExpiry().toString());
        assertEquals("测试套餐",info.offerings().path(0).path("offeringName").asText());
    }

    @Test void iccidMismatchIsPermanent() {
        var responses=new HashMap<>(happy());
        responses.put("query/sim-basic-info",new CarrierHttp.Response(200,
            "{\"status\":\"0\",\"result\":[{\"iccid\":\"89860000000000000002\",\"msisdn\":\"1\"}]}"));
        var provider=provider(responses);
        var session=provider.open(account);
        var failure=assertThrows(SupplierFailure.class,()->provider.fetch(session,account,"89860000000000000001"));
        assertEquals(SupplierFailure.Kind.PERMANENT,failure.kind());
        assertEquals("SUPPLIER_ICCID_MISMATCH",failure.code());
    }

    @Test void emptyBasicInfoIsPermanent() {
        var responses=new HashMap<>(happy());
        responses.put("query/sim-basic-info",new CarrierHttp.Response(200,"{\"status\":\"0\",\"result\":[]}"));
        var provider=provider(responses);
        var session=provider.open(account);
        var failure=assertThrows(SupplierFailure.class,()->provider.fetch(session,account,"89860000000000000001"));
        assertEquals(SupplierFailure.Kind.PERMANENT,failure.kind());
        assertEquals("SUPPLIER_CARD_NOT_FOUND",failure.code());
    }

    @Test void optionalEndpointFailureBecomesMissingField() {
        var responses=new HashMap<>(happy());
        responses.put("query/apn-info",new CarrierHttp.Response(200,"{\"status\":\"5001\",\"message\":\"接口暂不可用\"}"));
        var provider=provider(responses);
        var session=provider.open(account);
        var info=provider.fetch(session,account,"89860000000000000001");
        assertTrue(info.missing().contains("APN"));
    }

    @Test void serverErrorIsRetryable() {
        var responses=new HashMap<>(happy());
        responses.put("get/token",new CarrierHttp.Response(503,"busy"));
        var provider=provider(responses);
        var failure=assertThrows(SupplierFailure.class,()->provider.open(account));
        assertEquals(SupplierFailure.Kind.RETRYABLE,failure.kind());
        assertEquals("SUPPLIER_HTTP_503",failure.code());
    }
}
