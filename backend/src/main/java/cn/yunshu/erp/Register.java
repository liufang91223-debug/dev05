package cn.yunshu.erp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.yunshu.erp.Orders.*;

@RestController
@RequestMapping("/api")
class Register {
    private final Orders orders;
    private final JdbcTemplate db;
    final ObjectMapper json;
    static final Set<String> REFERENCES=Set.of("warehouse","category","settlement","operator","unit");
    private static final Map<String,List<String>> PAGE_FIELDS=Map.of(
        "goods",List.of("public_name","spec","category","unit","brand","safety_stock","memo"),
        "customer",List.of("level","manager","contact","phone","settlement","expiry","address","memo"),
        "supplier",List.of("level","manager","contact","phone","settlement","address","memo"),
        "warehouse",List.of("wh_type","address","manager","phone","locations","memo"),
        "category",List.of("parent","memo"),
        "unit",List.of("symbol","memo"),
        "operator",List.of("contact","phone","memo"),
        "settlement",List.of("basis","days","memo"));
    Register(Orders orders,JdbcTemplate db,ObjectMapper json) { this.orders=orders; this.db=db; this.json=json; }

    long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement(sql,new String[]{"id"});
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    @GetMapping("/erp/register/{family}")
    List<Map<String,Object>> list(@PathVariable String family) {
        List<Map<String,Object>> records;
        if (REFERENCES.contains(family)) {
            records=db.queryForList("SELECT * FROM base_reference WHERE family=? ORDER BY id DESC",family);
        } else if ("goods".equals(family)) {
            records=db.queryForList("SELECT * FROM sku ORDER BY id DESC");
        } else {
            require(Set.of("customer","supplier").contains(family),"档案类型无效");
            records=db.queryForList("SELECT * FROM party WHERE kind=? ORDER BY id DESC",family.toUpperCase(Locale.ROOT));
        }
        Map<Long,Map<String,Object>> fields=new HashMap<>();
        for(var row:db.queryForList("SELECT entity_id,details FROM master_profile WHERE family=?",family))
            fields.put(number(row,"entity_id"),decode(row.get("details").toString()));
        for(var row:records) {
            var detail=fields.getOrDefault(number(row,"id"),Map.of());
            detail.forEach((key,value)->{ if(!row.containsKey(key)) row.put(key,value); });
            row.putIfAbsent("enabled",true);
        }
        return records;
    }
    record Entry(String code,String name,String kind,BigDecimal price,Boolean enabled,String description,Map<String,String> details) {}
    @PostMapping("/erp/register/{family}") @Transactional
    Map<String,Long> create(@PathVariable String family,@RequestBody Entry data) { return save(family,0,data); }
    @PutMapping("/erp/register/{family}/{id}") @Transactional
    Map<String,Long> edit(@PathVariable String family,@PathVariable long id,@RequestBody Entry data) {
        require(id>0,"档案编号无效"); return save(family,id,data);
    }
    private Map<String,Long> save(String family,long id,Entry data) {
        String name=clean(data.name(),120), code=clean(data.code(),40);
        require(!name.isBlank(),"名称不能为空");
        boolean enabled=!Boolean.FALSE.equals(data.enabled());
        Map<String,Object> props=new LinkedHashMap<>();
        if(data.details()!=null) {
            require(data.details().size()<=30,"档案字段过多");
            data.details().forEach((k,v)->{require(k.matches("[a-z][a-z_]{0,39}"),"字段名称不正确");props.put(k,clean(v,250));});
        }

        if(REFERENCES.contains(family)) {
            boolean manualCode=family.equals("category") || family.equals("settlement");
            require(!manualCode || !code.isBlank(),"请填写档案编码");
            if(id==0) id=insert("INSERT INTO base_reference(family,code,name,description,enabled) VALUES(?,?,?,?,?)",family,code,name,clean(data.description(),250),enabled);
            else {
                orders.one("SELECT id FROM base_reference WHERE id=? AND family=? FOR UPDATE",id,family);
                db.update("UPDATE base_reference SET code=IF(?,?,code),name=?,description=?,enabled=? WHERE id=?",manualCode,code,name,clean(data.description(),250),enabled,id);
            }
        } else if(family.equals("goods")) {
            require(data.price()!=null && data.price().signum()>=0 && data.price().scale()<=2 &&
                data.kind()!=null && Set.of("HARDWARE","TERMINAL","GROUP").contains(data.kind()),"请检查商品编码、类别和价格");
            if(id==0) id=insert("INSERT INTO sku(code,name,kind,price) VALUES(?,?,?,?)",code,name,data.kind(),data.price());
            else {
                var old=orders.one("SELECT kind FROM sku WHERE id=? FOR UPDATE",id);
                if(!old.get("kind").equals(data.kind()))
                    require(db.queryForObject("SELECT COUNT(*) FROM order_line WHERE sku_id=?",Long.class,id)==0,"已产生订单的商品不能更换类型");
                db.update("UPDATE sku SET name=?,kind=?,price=? WHERE id=?",name,data.kind(),data.price(),id);
            }
        } else {
            require(Set.of("customer","supplier").contains(family),"档案类型无效");
            String kind=family.toUpperCase(Locale.ROOT);
            if(id==0) id=insert("INSERT INTO party(name,kind) VALUES(?,?)",name,kind);
            else {
                orders.one("SELECT id FROM party WHERE id=? AND kind=? FOR UPDATE",id,kind);
                db.update("UPDATE party SET name=? WHERE id=?",name,id);
            }
        }
        // Only identifiers from PAGE_FIELDS enter the SQL. All page values are bound parameters.
        String table=REFERENCES.contains(family)?"base_reference":family.equals("goods")?"sku":"party";
        var assignments=new ArrayList<String>();
        var values=new ArrayList<Object>();
        for(String field:PAGE_FIELDS.get(family)){
            String value=(String)props.remove(field);
            assignments.add("`"+field+"`=?");
            if(field.equals("expiry") && value!=null && !value.isBlank()){
                try { values.add(java.time.LocalDate.parse(value)); }
                catch(java.time.format.DateTimeParseException e){ throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"合同到期日格式无效"); }
            } else values.add(value==null || value.isBlank()?null:value);
        }
        if(!REFERENCES.contains(family)){assignments.add("enabled=?");values.add(enabled);}
        values.add(id);
        db.update("UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE id=?",values.toArray());
        if(!props.isEmpty()){
            String extra=encode(props);
            db.update("""
                INSERT INTO master_profile(family,entity_id,details) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE details=JSON_MERGE_PATCH(details,?)
                """,family,id,extra,extra);
        }
        return Map.of("id",id);
    }
    String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(Exception e){throw new IllegalArgumentException("字段格式错误",e);}
    }
    private Map<String,Object> decode(String value) {
        try {return json.readValue(value,new TypeReference<>(){});} catch(Exception e){throw new IllegalStateException("档案字段读取失败",e);}
    }
}
