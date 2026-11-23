package cn.yunshu.erp;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import static cn.yunshu.erp.Orders.require;

@RestController
@RequestMapping("/api/admin/master")
class MasterData {
    private final JdbcTemplate db;
    private final Orders orders;
    private final BCryptPasswordEncoder passwords;

    MasterData(JdbcTemplate db,Orders orders,BCryptPasswordEncoder passwords) {
        this.db=db; this.orders=orders; this.passwords=passwords;
    }

    record Party(String name,String kind) {}
    record Sku(String code,String name,String kind,BigDecimal price) {}
    record Account(String username,String password,String role,Long party) {}

    @GetMapping("/accounts") Object accounts() {
        return db.queryForList("SELECT id,username,role,party_id FROM account ORDER BY id");
    }
    @PostMapping("/parties") void createParty(@RequestBody Party data) {
        require(valid(data.name(),120) && Set.of("CUSTOMER","SUPPLIER").contains(data.kind()),"往来单位参数无效");
        db.update("INSERT INTO party(name,kind) VALUES(?,?)",data.name().trim(),data.kind());
    }
    @PostMapping("/skus") void createSku(@RequestBody Sku data) {
        require(valid(data.name(),120)
            && Set.of("HARDWARE","GROUP","TERMINAL").contains(data.kind())
            && data.price()!=null && data.price().signum()>=0 && data.price().scale()<=2
            && data.price().compareTo(new BigDecimal("99999999999999.99"))<=0,"商品参数无效");
        db.update("INSERT INTO sku(code,name,kind,price) VALUES(?,?,?,?)","",data.name().trim(),data.kind(),data.price());
    }
    @PostMapping("/accounts") void createAccount(@RequestBody Account data) {
        require(data.username()!=null && data.username().matches("[a-zA-Z0-9_.-]{3,80}")
            && data.password()!=null && data.password().length()>=12 && data.password().getBytes(StandardCharsets.UTF_8).length<=72
            && Set.of("ERP","CUSTOMER").contains(data.role()),"账号、密码或角色无效");
        if ("CUSTOMER".equals(data.role())) {
            require(data.party()!=null,"客户账号必须关联客户档案");
            orders.one("SELECT id FROM party WHERE id=? AND kind='CUSTOMER'",data.party());
        } else require(data.party()==null,"ERP 账号无需关联客户");
        db.update("INSERT INTO account(username,password_hash,role,party_id) VALUES(?,?,?,?)",
            data.username(),passwords.encode(data.password()),data.role(),data.party());
    }
    private boolean valid(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
}
