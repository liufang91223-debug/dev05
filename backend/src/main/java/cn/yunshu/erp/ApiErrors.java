package cn.yunshu.erp;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> business(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
            error.getReason()==null?"请求无法完成":error.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> constraint(DataIntegrityViolationException error) {
        if (error.getMostSpecificCause() instanceof java.sql.SQLException ex && ex.getErrorCode()==31001)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","本组流水号已用完，请增加编码规则的流水号长度"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","记录重复、已被引用或与当前数据不一致"));
    }
}
