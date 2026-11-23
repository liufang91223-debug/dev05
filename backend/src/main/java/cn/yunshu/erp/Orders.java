package cn.yunshu.erp;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Orders {
    private final JdbcTemplate db;
    Orders(JdbcTemplate db) { this.db = db; }

    record Line(long sku, int quantity, BigDecimal price, BigDecimal taxRate, String internalNote, String memo,Long carrierAccount) {
        Line(long sku,int quantity,BigDecimal price) { this(sku,quantity,price,null,null,null,null); }
    }
    record Header(java.time.LocalDate businessDay, java.time.LocalDate deliveryDay, String manager,
                  Long warehouse, String settlement, String externalCode, String notes, BigDecimal discount) {}
    record Draft(String direction, long party, List<Line> lines, Header header) {
        Draft(String direction,long party,List<Line> lines) { this(direction,party,lines,null); }
    }
    record Decision(boolean approved, String reason, long round, long step) {}
    record Inquiry(long sku, int quantity) {}

    @GetMapping("/csrf") CsrfToken csrf(CsrfToken token) { return token; }
    @GetMapping("/me") Map<String,Object> me(Principal user) {
        return one("SELECT id,username,role,party_id FROM account WHERE username=?", user.getName());
    }
    @GetMapping("/catalog") List<Map<String,Object>> catalog() {
        return db.queryForList("SELECT id,name,price FROM portal_catalog WHERE enabled=TRUE ORDER BY id");
    }
    @GetMapping("/erp/setup") Map<String,Object> setup() {
        return Map.of("approvalDirections",db.queryForList("SELECT DISTINCT direction FROM approval_step",String.class),
            "parties",db.queryForList("SELECT * FROM party ORDER BY id"),
            "skus", db.queryForList("SELECT * FROM sku ORDER BY id"),
            "accounts",db.queryForList("SELECT id,username FROM account WHERE role<>'CUSTOMER' ORDER BY id"),
            "carriers",db.queryForList("SELECT * FROM carrier_account ORDER BY id"));
    }
    @GetMapping("/erp/orders") List<Map<String,Object>> orders() {
        return db.queryForList("""
            SELECT o.*,p.name AS party_name,h.business_day,h.delivery_day,h.manager,h.warehouse_id,h.external_code,
                   (SELECT SUM(quantity*unit_price) FROM order_line WHERE order_id=o.id)-COALESCE(h.discount,0) AS total
            FROM erp_order o JOIN party p ON p.id=o.party_id LEFT JOIN order_header h ON h.order_id=o.id ORDER BY o.id DESC
            """);
    }
    @GetMapping("/erp/orders/{id}") Map<String,Object> order(@PathVariable long id) {
        return Map.of("order", one("SELECT o.*,p.name AS party_name,h.business_day,h.delivery_day,h.manager,h.warehouse_id,h.settlement,h.external_code,h.notes,h.discount FROM erp_order o JOIN party p ON p.id=o.party_id LEFT JOIN order_header h ON h.order_id=o.id WHERE o.id=?", id),
            "lines", db.queryForList("SELECT l.*,s.name,s.code,s.kind,pa.carrier_account_id,x.tax_rate,x.internal_note,x.memo FROM order_line l JOIN sku s ON l.sku_id=s.id LEFT JOIN order_line_extra x ON x.order_id=l.order_id AND x.sku_id=l.sku_id LEFT JOIN purchase_line_account pa ON pa.order_id=l.order_id AND pa.sku_id=l.sku_id WHERE l.order_id=? ORDER BY l.sku_id", id),
            "history", db.queryForList("SELECT * FROM approval_log WHERE order_id=? ORDER BY id", id));
    }
    @GetMapping("/erp/inventory") List<Map<String,Object>> inventory() {
        return db.queryForList("SELECT s.id,s.code,s.name,COALESCE(i.on_hand,0) AS on_hand,COALESCE(i.reserved,0) AS reserved FROM sku s LEFT JOIN inventory i ON i.sku_id=s.id ORDER BY s.id");
    }

    @PostMapping("/erp/orders") @Transactional
    Map<String,Long> create(@RequestBody Draft draft, Principal user) {
        return Map.of("id", insertDraft(draft, null, actor(user)));
    }
    @PutMapping("/erp/orders/{id}") @Transactional
    void edit(@PathVariable long id, @RequestBody Draft draft) {
        var old = lockOrder(id,"DRAFT");
        require(old.get("direction").equals(draft.direction()),"不能更改单据类型");
        require(old.get("inquiry_id") == null || number(old,"party_id") == draft.party(),"申请转单不能更换客户");
        validate(draft);
        db.update("UPDATE erp_order SET party_id=? WHERE id=?",draft.party(),id);
        db.update("DELETE FROM order_line WHERE order_id=?", id);
        saveLines(id,draft.lines());
        saveHeader(id,draft);
    }

    long insertDraft(Draft draft, Long inquiry, long actor) {
        validate(draft);
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var stmt = connection.prepareStatement("INSERT INTO erp_order(direction,party_id,inquiry_id,creator_id) VALUES(?,?,?,?)",
                new String[]{"id"});
            stmt.setString(1,draft.direction());
            stmt.setLong(2,draft.party());
            stmt.setObject(3,inquiry);
            stmt.setLong(4,actor);
            return stmt;
        },key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        saveLines(id,draft.lines());
        saveHeader(id,draft);
        return id;
    }

    void validate(Draft draft) {
        require("SALE".equals(draft.direction()) || "PURCHASE".equals(draft.direction()),"单据类型无效");
        String kind = "SALE".equals(draft.direction()) ? "CUSTOMER" : "SUPPLIER";
        one("SELECT id FROM party WHERE id=? AND kind=?",draft.party(),kind);
        require(draft.lines()!=null && !draft.lines().isEmpty() && draft.lines().size()<=100,"请提供 1 到 100 行商品");
        var skus = new HashSet<Long>();
        for (Line line : draft.lines()) {
            require(line != null && skus.add(line.sku()) && line.quantity()>0 && line.quantity()<=10000
                && line.price()!=null && line.price().signum()>=0 && line.price().scale()<=2
                && line.price().compareTo(new BigDecimal("99999999999999.99"))<=0,"商品、数量或单价无效");
            one("SELECT id FROM sku WHERE id=?",line.sku());
        }
    }

    void saveLines(long id, List<Line> lines) {
        for (Line line : lines) {
            db.update("INSERT INTO order_line VALUES(?,?,?,?)",id,line.sku(),line.quantity(),line.price());
            var tax=line.taxRate()==null ? BigDecimal.valueOf(13) : line.taxRate();
            require(Set.of(0,3,6,9,13).contains(tax.intValue()) && tax.stripTrailingZeros().scale()<=0,"税率无效");
            if(line.carrierAccount()!=null){one("SELECT id FROM carrier_account WHERE id=?",line.carrierAccount());db.update("INSERT INTO purchase_line_account VALUES(?,?,?)",id,line.sku(),line.carrierAccount());}
            db.update("INSERT INTO order_line_extra VALUES(?,?,?,?,?)",id,line.sku(),tax,clean(line.internalNote(),200),clean(line.memo(),200));
        }
    }
    static String clean(String value,int max) {
        require(value==null || value.length()<=max,"文本超过长度限制");
        return value==null?"":value.strip();
    }
    private void saveHeader(long id,Draft draft) {
        var h=draft.header();
        var day=h==null || h.businessDay()==null ? Cards.today() : h.businessDay();
        var delivery=h==null?null:h.deliveryDay();
        require(delivery==null || !delivery.isBefore(day),"交货日期不能早于订单日期");
        long warehouse=h==null || h.warehouse()==null ? 1L : h.warehouse();
        one("SELECT id FROM base_reference WHERE id=? AND family='warehouse' AND enabled=TRUE",warehouse);
        var discount=h==null || h.discount()==null ? BigDecimal.ZERO : h.discount();
        var gross=draft.lines().stream().map(l->l.price().multiply(BigDecimal.valueOf(l.quantity()))).reduce(BigDecimal.ZERO,BigDecimal::add);
        require(discount.signum()>=0 && discount.scale()<=2 && discount.compareTo(gross)<=0,"优惠金额不能超过商品金额");
        db.update("DELETE FROM order_header WHERE order_id=?",id);
        db.update("INSERT INTO order_header VALUES(?,?,?,?,?,?,?,?,?)",id,day,delivery,clean(h==null?null:h.manager(),100),warehouse,
            clean(h==null?null:h.settlement(),120),clean(h==null?null:h.externalCode(),90),clean(h==null?null:h.notes(),250),discount);
    }

    @PostMapping("/erp/orders/{id}/submit") @Transactional
    void submit(@PathVariable long id, Principal user) {
        var order = lockOrder(id,"DRAFT");
        int round = Math.toIntExact(number(order,"round_no")) + 1;
        db.update("""
            INSERT INTO approval_assignment(order_id,round_no,step_no,account_id)
            SELECT ?,?,step_no,account_id FROM approval_step WHERE direction=?
            """,id,round,order.get("direction"));
        db.update("UPDATE erp_order SET state='PENDING',round_no=?,current_step=1 WHERE id=?",round,id);
        log(id,round,0,actor(user),"SUBMIT",null);
    }
    @PostMapping("/erp/orders/{id}/decision") @Transactional
    void decide(@PathVariable long id,@RequestBody Decision decision, Principal user) {
        var order = lockOrder(id,"PENDING");
        long round = number(order,"round_no"), step = number(order,"current_step"), actor = actor(user);
        if (round != decision.round() || step != decision.step()) throw new ResponseStatusException(HttpStatus.CONFLICT,"审批节点已变化");
        var assignments = db.queryForList("SELECT account_id FROM approval_assignment WHERE order_id=? AND round_no=? AND step_no=?",id,round,step);
        if (!assignments.isEmpty() && assignments.stream().noneMatch(a -> number(a,"account_id") == actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前节点未指派给你");
        if (!decision.approved()) {
            require(decision.reason()!=null && !decision.reason().isBlank() && decision.reason().length()<=300,"填写驳回原因");
            db.update("UPDATE erp_order SET state='DRAFT',current_step=0 WHERE id=?",id);
            log(id,round,step,actor,"REJECT",decision.reason());
            return;
        }
        var next = db.queryForObject("SELECT MIN(step_no) FROM approval_assignment WHERE order_id=? AND round_no=? AND step_no>?",Long.class,id,round,step);
        if (next != null) {
            db.update("UPDATE erp_order SET current_step=? WHERE id=?",next,id);
        } else {
            if ("SALE".equals(order.get("direction"))) {
                for (var line : db.queryForList("SELECT * FROM order_line WHERE order_id=? ORDER BY sku_id",id)) {
                    long quantity = number(line,"quantity");
                    long warehouse=warehouse(id);
                    require(db.update("UPDATE warehouse_stock SET reserved=reserved+? WHERE warehouse_id=? AND sku_id=? AND on_hand-reserved>=?",quantity,warehouse,number(line,"sku_id"),quantity)==1,"发货仓库可用库存不足");
                    require(db.update("UPDATE inventory SET reserved=reserved+? WHERE sku_id=? AND on_hand-reserved>=?",
                        quantity,number(line,"sku_id"),quantity)==1,"可用库存不足");
                }
            }
            db.update("UPDATE erp_order SET state='APPROVED',current_step=0 WHERE id=?",id);
        }
        log(id,round,step,actor,"APPROVE",null);
    }

    @PutMapping("/admin/approval/{direction}") @Transactional
    void approval(@PathVariable String direction,@RequestBody List<Set<Long>> steps) {
        require(Set.of("SALE","PURCHASE").contains(direction) && steps.size()<=20,"审批配置无效");
        db.update("DELETE FROM approval_step WHERE direction=?",direction);
        for (int i=0; i<steps.size(); i++) {
            require(steps.get(i)!=null && !steps.get(i).isEmpty(),"审批节点不能为空");
            for (long user : steps.get(i)) {
                one("SELECT id FROM account WHERE id=? AND role<>'CUSTOMER'",user);
                db.update("INSERT INTO approval_step VALUES(?,?,?)",direction,i+1,user);
            }
        }
    }
    @GetMapping("/admin/approval") List<Map<String,Object>> approval() {
        return db.queryForList("SELECT * FROM approval_step ORDER BY direction,step_no,account_id");
    }

    @PostMapping("/portal/inquiries") void inquiry(@RequestBody Inquiry body, Principal user) {
        require(body.quantity()>0 && body.quantity()<=10000,"数量无效");
        one("SELECT id FROM portal_catalog WHERE id=? AND enabled=TRUE",body.sku());
        db.update("INSERT INTO inquiry(customer_id,sku_id,quantity) VALUES(?,?,?)",customer(user),body.sku(),body.quantity());
    }
    @GetMapping("/portal/inquiries") List<Map<String,Object>> myInquiries(Principal user) {
        return project("inquiries",db.queryForList("""
            SELECT i.id,s.name,i.quantity,i.created_at,CASE WHEN o.id IS NULL THEN 'DRAFT' ELSE 'CONVERTED' END AS state,o.id AS order_id
            FROM inquiry i JOIN portal_catalog s ON s.id=i.sku_id LEFT JOIN erp_order o ON o.inquiry_id=i.id
            WHERE i.customer_id=? ORDER BY i.id DESC LIMIT 500
            """, customer(user)));
    }
    @GetMapping("/erp/inquiries") List<Map<String,Object>> inquiries() {
        return db.queryForList("""
            SELECT i.*,s.name,p.name AS customer_name FROM inquiry i
            JOIN sku s ON s.id=i.sku_id JOIN party p ON p.id=i.customer_id
            LEFT JOIN erp_order o ON o.inquiry_id=i.id WHERE o.id IS NULL ORDER BY i.id DESC LIMIT 500
            """);
    }
    @PostMapping("/erp/inquiries/{id}/convert") @Transactional
    Map<String,Long> convert(@PathVariable long id, Principal user) {
        var row = one("SELECT * FROM inquiry WHERE id=? FOR UPDATE",id);
        var found = db.queryForList("SELECT id FROM erp_order WHERE inquiry_id=?",id);
        if (!found.isEmpty()) return Map.of("id",number(found.getFirst(),"id"));
        long sku = number(row,"sku_id");
        var price = db.queryForObject("SELECT price FROM sku WHERE id=?",BigDecimal.class,sku);
        return Map.of("id", insertDraft(new Draft("SALE",number(row,"customer_id"),
            List.of(new Line(sku,Math.toIntExact(number(row,"quantity")),price))),id,actor(user)));
    }
    @GetMapping("/portal/orders") List<Map<String,Object>> myOrders(Principal user) {
        return project("orders",db.queryForList("""
            SELECT o.id,o.state,o.created_at,SUM(l.quantity*l.unit_price)-COALESCE((SELECT discount FROM order_header WHERE order_id=o.id),0) AS total
            FROM erp_order o JOIN order_line l ON l.order_id=o.id
            WHERE o.direction='SALE' AND o.party_id=?
            GROUP BY o.id,o.state,o.created_at ORDER BY o.id DESC LIMIT 500
            """,customer(user)));
    }

    static final Map<String,Set<String>> PORTAL_FIELDS = Map.of(
        "orders",Set.of("id","state","total","created_at"),
        "inquiries",Set.of("id","name","quantity","created_at","state","order_id"),
        "cards",Set.of("iccid","msisdn","imei","plan_name","activated_on","erp_expiry","quota_kb","used_kb","remaining_kb","starts_on","ends_on","updated_at"));
    @GetMapping("/admin/portal-fields") Map<String,List<String>> fields() {
        var result = new TreeMap<String,List<String>>();
        PORTAL_FIELDS.keySet().forEach(resource -> result.put(resource,
            db.queryForList("SELECT field_key FROM portal_field WHERE resource=? ORDER BY field_key",String.class,resource)));
        return result;
    }
    @PutMapping("/admin/portal-fields/{resource}") @Transactional
    void fields(@PathVariable String resource,@RequestBody Set<String> fields) {
        require(PORTAL_FIELDS.containsKey(resource) && PORTAL_FIELDS.get(resource).containsAll(fields),"字段不在客户可见清单内");
        db.update("DELETE FROM portal_field WHERE resource=?",resource);
        fields.forEach(f -> db.update("INSERT INTO portal_field VALUES(?,?)",resource,f));
    }
    List<Map<String,Object>> project(String resource,List<Map<String,Object>> rows) {
        var keys = db.queryForList("SELECT field_key FROM portal_field WHERE resource=?",String.class,resource);
        return rows.stream().map(row -> {
            Map<String,Object> fields = new LinkedHashMap<>();
            keys.forEach(k -> fields.put(k,row.get(k)));
            return fields;
        }).toList();
    }
    Map<String,Object> lockOrder(long id,String state) {
        var row = one("SELECT * FROM erp_order WHERE id=? FOR UPDATE",id);
        if (!state.equals(row.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"单据状态已变化，请刷新");
        return row;
    }
    long warehouse(long id) {
        var warehouses=db.queryForList("SELECT warehouse_id FROM order_header WHERE order_id=?",Long.class,id);
        return warehouses.isEmpty()?1L:warehouses.getFirst();
    }
    void log(long id,long round,long step,long actor,String action,String reason) {
        db.update("INSERT INTO approval_log(order_id,round_no,step_no,actor_id,action,reason) VALUES(?,?,?,?,?,?)",id,round,step,actor,action,reason);
    }
    long actor(Principal user) { return number(me(user),"id"); }
    long customer(Principal user) {
        var party = me(user).get("party_id");
        if (!(party instanceof Number id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号尚未关联客户");
        return id.longValue();
    }
    Map<String,Object> one(String sql,Object... args) {
        return db.queryForList(sql,args).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"没有匹配记录"));
    }
    static void require(boolean condition,String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
