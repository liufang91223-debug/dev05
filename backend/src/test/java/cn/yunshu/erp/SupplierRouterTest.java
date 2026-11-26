package cn.yunshu.erp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupplierRouterTest {
    private CardInfoProvider provider(String key) {
        return new CardInfoProvider(){
            public String adapterKey(){return key;}
            public Session open(SupplierAccount account){return new Session(account,"t");}
            public CardInfoResult fetch(Session session,SupplierAccount account,String iccid){return null;}
            public JsonNode call(Session session,String endpoint,Map<String,String> query){return null;}
            public JsonNode optional(Session session,String endpoint,Map<String,String> query,List<String> missing,String label){return null;}
        };
    }
    private SupplierAccount account(String adapter,String sub) {
        return new SupplierAccount(1,"a","R",true,"CMCC","CMCC",sub,adapter,null);
    }

    @Test void routesByAdapterKey() {
        var provider=provider("cmcc-ctboss");
        var router=new SupplierRouter(List.of(provider));
        assertSame(provider,router.forAccount(account("cmcc-ctboss","CMCC_CTBOSS")));
    }

    @Test void fallsBackToApiSubCategory() {
        var provider=provider("cmcc-onlink");
        var router=new SupplierRouter(List.of(provider));
        assertSame(provider,router.forAccount(account(null,"CMCC_ONELINK")));
    }

    @Test void unknownAdapterIsConfigFailure() {
        var router=new SupplierRouter(List.of(provider("cmcc-ctboss")));
        var failure=assertThrows(SupplierFailure.class,()->router.forAccount(account("unicom-jasper","UNICOM_JASPER")));
        assertEquals(SupplierFailure.Kind.CONFIG,failure.kind());
        assertEquals("SUPPLIER_ADAPTER_UNKNOWN",failure.code());
    }
}
