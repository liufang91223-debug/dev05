package cn.yunshu.erp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CarrierSyncTest {
    @Test void classifiesFailures() {
        assertEquals(SupplierFailure.Kind.RETRYABLE,CarrierSync.classify(new RuntimeException("io")));
        assertEquals(SupplierFailure.Kind.PERMANENT,
            CarrierSync.classify(SupplierFailure.permanent("X","bad")));
        assertEquals(SupplierFailure.Kind.CONFIG,
            CarrierSync.classify(SupplierFailure.config("X","missing")));
        assertEquals(SupplierFailure.Kind.PERMANENT,
            CarrierSync.classify(new ResponseStatusException(HttpStatus.CONFLICT,"disabled")));
        assertEquals(SupplierFailure.Kind.RETRYABLE,
            CarrierSync.classify(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"upstream")));
    }

    @Test void backoffGrowsAndCaps() {
        assertEquals(5,CarrierSync.backoffMinutes(1));
        assertEquals(10,CarrierSync.backoffMinutes(2));
        assertEquals(20,CarrierSync.backoffMinutes(3));
        assertEquals(40,CarrierSync.backoffMinutes(4));
        assertEquals(60,CarrierSync.backoffMinutes(5));
        assertEquals(60,CarrierSync.backoffMinutes(20));
    }

    @Test void errorMessageIsSingleLineAndBounded() {
        String message=CarrierSync.message(new SupplierFailure(SupplierFailure.Kind.RETRYABLE,"CMCC_5001",
            "第一行\n第二行\t"+"x".repeat(900)));
        assertTrue(message.startsWith("CMCC_5001 "));
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("\t"));
        assertEquals(500,message.length());
    }
}
