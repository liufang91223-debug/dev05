package cn.yunshu.erp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static cn.yunshu.erp.Orders.require;

@RestController
@RequestMapping("/api/erp/code-rules")
class CodeRules {
    private final JdbcTemplate db;
    CodeRules(JdbcTemplate db) { this.db=db; }

    @GetMapping
    List<Map<String,Object>> list() {
        return db.queryForList("SELECT * FROM code_rule ORDER BY display_order");
    }

    @PutMapping("/{key}") @Transactional
    void update(@PathVariable String key,@RequestBody JsonNode body) {
        var rules=db.queryForList("SELECT date_policy,revision FROM code_rule WHERE rule_key=? FOR UPDATE",key);
        if(rules.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"编码规则不存在");
        var rule=rules.getFirst();
        require(body.isObject() && body.size()==4 && body.path("includeDate").isBoolean()
            && body.path("serialLength").isInt() && body.path("initialValue").isTextual()
            && body.path("revision").isIntegralNumber() && body.path("revision").canConvertToLong(),"编码规则参数无效");
        if(body.get("revision").longValue()!=((Number)rule.get("revision")).longValue())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"规则已被修改，请重新打开后编辑");
        boolean includeDate=body.get("includeDate").booleanValue();
        String policy=(String)rule.get("date_policy");
        require(policy.equals("OPTIONAL") || includeDate==policy.equals("ALWAYS"),"该类型的日期前缀不可修改");
        int length=body.get("serialLength").intValue();
        String initial=body.get("initialValue").textValue();
        require(length>=1 && length<=5,"流水号长度须为 1 至 5 位");
        require(initial.matches("[0-9]{1,5}") && initial.length()<=length,"初始值须为不超过流水号长度的数字");
        db.update("UPDATE code_rule SET include_date=?,serial_length=?,initial_value=?,revision=revision+1 WHERE rule_key=?",
            includeDate,length,Integer.parseInt(initial),key);
    }
}
