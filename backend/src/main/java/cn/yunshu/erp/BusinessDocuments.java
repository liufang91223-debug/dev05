package cn.yunshu.erp;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.yunshu.erp.Orders.*;

@RestController
@RequestMapping("/api/erp/documents")
class BusinessDocuments {
    static final Set<String> TYPES=Set.of("purchase_inbound","sales_outbound","other_inbound","other_outbound","sales_return","purchase_return",
        "sales_exchange","stock_taking","transfer","sales_invoice","purchase_invoice","receipt","payment");
    static final Set<String> FINANCE=Set.of("sales_invoice","purchase_invoice","receipt","payment");
    static final Set<String> SHIPPING=Set.of("purchase_inbound","sales_outbound");
    static final Set<String> RETURNS=Set.of("sales_return","purchase_return");
    private final JdbcTemplate db;
    private final Orders orders;
    private final Register register;
    private final Cards cards;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    BusinessDocuments(JdbcTemplate db,Orders orders,Register register,Cards cards,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json=json;
        this.db=db; this.orders=orders; this.register=register; this.cards=cards;
    }

    record Item(long sku,int quantity,List<Cards.CardItem> cards,LocalDate expiry,String replacement) {}
    record Draft(String kind,Long sourceOrder,Long sourceDoc,Long warehouse,Long targetWarehouse,
                 LocalDate day,BigDecimal amount,String memo,List<Item> lines,Integer revision) {}
    @GetMapping Object list(@RequestParam(required=false) String kind) {
        if(kind!=null) require(TYPES.contains(kind),"单据类型无效");
        String query="""
            SELECT d.*,p.name AS party_name,w.name AS warehouse_name,t.name AS target_name,a.username AS creator
            FROM business_document d LEFT JOIN party p ON p.id=d.party_id
            LEFT JOIN base_reference w ON w.id=d.warehouse_id LEFT JOIN base_reference t ON t.id=d.target_warehouse
            JOIN account a ON a.id=d.created_by
            """;
        return kind==null ? db.queryForList(query+" ORDER BY d.id DESC") : db.queryForList(query+" WHERE d.kind=? ORDER BY d.id DESC",kind);
    }
    @GetMapping("/{id}") Map<String,Object> detail(@PathVariable long id) {
        var record=orders.one("SELECT d.*,p.name AS party_name FROM business_document d LEFT JOIN party p ON p.id=d.party_id WHERE d.id=?",id);
        record.put("lines",items(record));
        return record;
    }
    @PostMapping @Transactional Map<String,Long> create(@RequestBody Draft d,Principal user) { return save(0,d,orders.actor(user)); }
    @PutMapping("/{id}") @Transactional Map<String,Long> update(@PathVariable long id,@RequestBody Draft d,Principal user) { return save(id,d,orders.actor(user)); }

    private Map<String,Long> save(long id,Draft draft,long actor) {
        String kind=draft.kind();
        require(kind!=null && TYPES.contains(kind) && draft.day()!=null,"需要单据类型和日期");
        if(id!=0) {
            var old=orders.one("SELECT * FROM business_document WHERE id=? FOR UPDATE",id);
            require(old.get("kind").equals(kind) && old.get("state").equals("DRAFT") && draft.revision()!=null &&
                number(old,"revision")==draft.revision(),"单据已被修改或提交，请刷新");
        }
        Long source=draft.sourceOrder(), invoice=draft.sourceDoc(), party=null, warehouse=draft.warehouse();
        List<Item> lines=draft.lines()==null?List.of():draft.lines();
        require(lines.size()<=100,"明细超过 100 行");
        BigDecimal amount=draft.amount()==null?BigDecimal.ZERO:draft.amount();
        require(amount.signum()>=0 && amount.scale()<=2,"金额格式不正确");
        if(kind.equals("receipt") || kind.equals("payment")) {
            require(invoice!=null,"请选择要核销的发票");
            var parent=orders.one("SELECT * FROM business_document WHERE id=? AND state='POSTED' AND kind=?",invoice,kind.equals("receipt")?"sales_invoice":"purchase_invoice");
            source=number(parent,"source_order");
        } else require(invoice==null,"该单据不参照发票");
        if(SHIPPING.contains(kind) || FINANCE.contains(kind) || RETURNS.contains(kind) || kind.equals("sales_exchange")) {
            require(source!=null,"请选择来源订单");
            var order=orders.one("SELECT * FROM erp_order WHERE id=?",source);
            boolean sale=Set.of("sales_outbound","sales_return","sales_exchange","sales_invoice","receipt").contains(kind);
            require(order.get("direction").equals(sale?"SALE":"PURCHASE"),"订单类型与单据不匹配");
            require(order.get("state").equals(SHIPPING.contains(kind)?"APPROVED":"FULFILLED"),"来源订单当前状态不可参照");
            party=number(order,"party_id"); warehouse=orders.warehouse(source);
            if(SHIPPING.contains(kind)) amount=orderTotal(source);
        } else require(source==null,"该单据使用手工明细");
        if(FINANCE.contains(kind)) require(amount.signum()>0,"请输入正数金额");
        else {
            require(warehouse!=null,"请选择仓库");
            orders.one("SELECT id FROM base_reference WHERE family='warehouse' AND enabled=TRUE AND id=?",warehouse);
            if(kind.equals("transfer")) {
                require(draft.targetWarehouse()!=null && !Objects.equals(warehouse,draft.targetWarehouse()),"请选择不同的调入仓库");
                orders.one("SELECT id FROM base_reference WHERE family='warehouse' AND enabled=TRUE AND id=?",draft.targetWarehouse());
            }
            Set<Long> skus=new HashSet<>();
            for(var line:lines) {
                require(line!=null && skus.add(line.sku()) && line.quantity()>=0 && line.quantity()<=10000,"商品重复或数量不正确");
                orders.one("SELECT id FROM sku WHERE id=?",line.sku());
            }
        }
        String payload=register.encode(lines), memo=clean(draft.memo(),250);
        if(id==0) id=register.insert("""
            INSERT INTO business_document(kind,source_order,source_doc,party_id,warehouse_id,target_warehouse,business_day,amount,memo,`lines`,created_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,kind,source,invoice,party,warehouse,draft.targetWarehouse(),draft.day(),amount,memo,payload,actor);
        else db.update("""
            UPDATE business_document SET source_order=?,source_doc=?,party_id=?,warehouse_id=?,target_warehouse=?,business_day=?,amount=?,memo=?,
            `lines`=?,revision=revision+1 WHERE id=?
            """,source,invoice,party,warehouse,draft.targetWarehouse(),draft.day(),amount,memo,payload,id);
        return Map.of("id",id);
    }
    BigDecimal orderTotal(long id) {
        return db.queryForObject("""
            SELECT SUM(quantity*unit_price)-COALESCE((SELECT discount FROM order_header WHERE order_id=?),0)
            FROM order_line WHERE order_id=?
            """,BigDecimal.class,id,id);
    }
    List<Item> items(Map<String,Object> d) {
        try{return json.readValue(d.get("lines").toString(),new TypeReference<>(){});}
        catch(Exception ex){throw new IllegalStateException("单据明细读取失败",ex);}
    }
    record Transition(String action,int revision) {}
    @PostMapping("/{id}/transition") @Transactional
    void transition(@PathVariable long id,@RequestBody Transition t,Principal user) {
        var d=orders.one("SELECT * FROM business_document WHERE id=? FOR UPDATE",id);
        require(number(d,"revision")==t.revision(),"单据已变化，请刷新");
        String stage=d.get("state").toString(), kind=d.get("kind").toString(), next;
        if("submit".equals(t.action()) && stage.equals("DRAFT")) {
            require(FINANCE.contains(kind) || !items(d).isEmpty(),"请填写明细");
            next="PENDING";
        } else if("reject".equals(t.action()) && stage.equals("PENDING")) next="DRAFT";
        else if("post".equals(t.action()) && stage.equals("PENDING")) {
            if(SHIPPING.contains(kind)) {
                long source=number(d,"source_order");
                var provided=items(d);
                var required=db.queryForList("SELECT sku_id,quantity FROM order_line WHERE order_id=? ORDER BY sku_id",source);
                require(required.size()==provided.size(),"出入库单据必须包含全部来源明细");
                for(var row:required) require(provided.stream().anyMatch(i->i.sku()==number(row,"sku_id") && i.quantity()==number(row,"quantity")),"整单出入库数量与订单不一致");
                cards.fulfill(source,new Cards.Fulfillment(provided.stream().map(i->new Cards.Movement(i.sku(),i.cards()==null?List.of():i.cards(),i.expiry())).toList()),user);
            } else if(kind.equals("sales_exchange")) {
                long source=number(d,"source_order");
                orders.lockOrder(source,"FULFILLED");
                for(var line:items(d)){
                    require(line.quantity()==1 && line.cards()!=null && line.cards().size()==1 && line.replacement()!=null,"请逐行选择原卡和替换卡");
                    var old=line.cards().getFirst().iccid();
                    orders.one("SELECT iccid FROM sim_card WHERE iccid=? AND sale_order_id=? AND sku_id=? AND customer_id=? FOR UPDATE",old,source,line.sku(),d.get("party_id"));
                    cards.exchange(old,new Cards.Exchange(line.replacement()),user);
                }
            } else if(FINANCE.contains(kind)) finance(d);
            else if(RETURNS.contains(kind)) returns(d,user);
            else stock(d,false);
            next=kind.equals("transfer")?"TRANSIT":"POSTED";
        } else if("receive".equals(t.action()) && stage.equals("TRANSIT") && kind.equals("transfer")) {
            stock(d,true); next="POSTED";
        } else throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"当前状态不支持此操作");
        db.update("UPDATE business_document SET state=?,revision=revision+1,posted_by=?,posted_at=CURRENT_TIMESTAMP WHERE id=?",next,orders.actor(user),id);
    }
    private void finance(Map<String,Object> d) {
        long source=number(d,"source_order");
        orders.lockOrder(source,"FULFILLED");
        String kind=d.get("kind").toString();
        BigDecimal amount=(BigDecimal)d.get("amount");
        if(kind.equals("receipt") || kind.equals("payment")) {
            var invoice=orders.one("SELECT amount FROM business_document WHERE id=? AND state='POSTED' FOR UPDATE",d.get("source_doc"));
            var paid=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM business_document WHERE source_doc=? AND kind=? AND state='POSTED'",BigDecimal.class,d.get("source_doc"),kind);
            require(paid.add(amount).compareTo((BigDecimal)invoice.get("amount"))<=0,"核销金额超过发票未核销额");
        } else {
            var invoiced=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM business_document WHERE source_order=? AND kind=? AND state='POSTED'",BigDecimal.class,source,kind);
            var returned=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM business_document WHERE source_order=? AND kind IN ('sales_return','purchase_return') AND state='POSTED'",BigDecimal.class,source);
            require(invoiced.add(amount).compareTo(orderTotal(source).subtract(returned))<=0,"开票金额超过已出入库未开票金额");
        }
    }
    private void returns(Map<String,Object> d,Principal user) {
        long source=number(d,"source_order"), warehouse=number(d,"warehouse_id");
        orders.lockOrder(source,"FULFILLED");
        boolean sale=d.get("kind").equals("sales_return");
        BigDecimal total=BigDecimal.ZERO;
        var previous=db.queryForList("SELECT `lines` FROM business_document WHERE source_order=? AND kind=? AND state='POSTED'",source,d.get("kind"));
        var gross=db.queryForObject("SELECT SUM(quantity*unit_price) FROM order_line WHERE order_id=?",BigDecimal.class,source);
        for(var item:items(d)) {
            var line=orders.one("SELECT l.*,s.kind FROM order_line l JOIN sku s ON s.id=l.sku_id WHERE order_id=? AND sku_id=?",source,item.sku());
            int prior=previous.stream().flatMap(r->items(r).stream()).filter(i->i.sku()==item.sku()).mapToInt(Item::quantity).sum();
            require(item.quantity()>0 && item.quantity()+prior<=number(line,"quantity"),"退货数量超过原单剩余可退数量");
            if("HARDWARE".equals(line.get("kind"))) adjust(warehouse,item.sku(),sale?item.quantity():-item.quantity());
            else {
                require(item.cards()!=null && item.cards().size()==item.quantity(),"请逐张选择原卡");
                for(var c:item.cards()) {
                    if(sale) {
                        orders.one("SELECT iccid FROM sim_card WHERE iccid=? AND sale_order_id=? AND sku_id=? AND customer_id IS NOT NULL FOR UPDATE",c.iccid(),source,item.sku());
                        cards.returnCard(c.iccid(),user);
                    } else {
                        orders.one("SELECT c.iccid FROM sim_card c JOIN card_location l ON l.iccid=c.iccid WHERE c.iccid=? AND c.purchase_order_id=? AND c.sku_id=? AND c.customer_id IS NULL AND l.warehouse_id=? FOR UPDATE",c.iccid(),source,item.sku(),warehouse);
                        db.update("DELETE FROM card_location WHERE iccid=?",c.iccid());
                        adjust(warehouse,item.sku(),-1);
                        cards.event(c.iccid(),orders.actor(user),"RETURN_SUPPLIER","采购单 "+source);
                    }
                }
            }
            total=total.add(((BigDecimal)line.get("unit_price")).multiply(BigDecimal.valueOf(item.quantity())));
        }
        BigDecimal net=gross.signum()==0?BigDecimal.ZERO:total.multiply(orderTotal(source)).divide(gross,2,java.math.RoundingMode.HALF_UP);
        db.update("UPDATE business_document SET amount=? WHERE id=?",net,d.get("id"));
    }
    private void stock(Map<String,Object> d,boolean arrival) {
        String kind=d.get("kind").toString();
        long warehouse=number(d,arrival?"target_warehouse":"warehouse_id");
        for(var item:items(d).stream().sorted(Comparator.comparingLong(Item::sku)).toList()) {
            var sku=orders.one("SELECT kind FROM sku WHERE id=?",item.sku());
            require("HARDWARE".equals(sku.get("kind")),"其它出入库、盘点和调拨当前支持数量管理设备");
            int delta=item.quantity();
            if(kind.equals("stock_taking")) {
                db.update("INSERT INTO warehouse_stock(warehouse_id,sku_id) VALUES(?,?) ON DUPLICATE KEY UPDATE sku_id=sku_id",warehouse,item.sku());
                delta-=Math.toIntExact(number(orders.one("SELECT on_hand FROM warehouse_stock WHERE warehouse_id=? AND sku_id=? FOR UPDATE",warehouse,item.sku()),"on_hand"));
            } else {
                require(delta>0,"明细数量必须大于零");
                if(kind.equals("other_outbound") || (kind.equals("transfer") && !arrival)) delta=-delta;
            }
            adjust(warehouse,item.sku(),delta);
        }
    }
    private void adjust(long warehouse,long sku,int delta) {
        db.update("INSERT INTO warehouse_stock(warehouse_id,sku_id) VALUES(?,?) ON DUPLICATE KEY UPDATE sku_id=sku_id",warehouse,sku);
        db.update("INSERT INTO inventory(sku_id) VALUES(?) ON DUPLICATE KEY UPDATE sku_id=sku_id",sku);
        require(db.update("UPDATE warehouse_stock SET on_hand=on_hand+? WHERE warehouse_id=? AND sku_id=? AND on_hand+?>=reserved",delta,warehouse,sku,delta)==1,"仓库可用库存不足");
        require(db.update("UPDATE inventory SET on_hand=on_hand+? WHERE sku_id=? AND on_hand+?>=reserved",delta,sku,delta)==1,"商品可用库存不足");
    }
}

@RestController
@RequestMapping("/api/erp/orders")
class OrderFulfillment {
    private final Orders orders;
    private final BusinessDocuments docs;
    private final JdbcTemplate db;
    OrderFulfillment(Orders orders,BusinessDocuments docs,JdbcTemplate db) {
        this.orders=orders;this.docs=docs;this.db=db;
    }

    // Older clients confirm an order directly. Record the same warehouse document as the console.
    @PostMapping("/{id}/fulfill") @Transactional
    void fulfill(@PathVariable long id,@RequestBody Cards.Fulfillment body,Principal actor) {
        var order=orders.lockOrder(id,"APPROVED");
        require(body.lines()!=null,"请填写出入库明细");
        var supplied=new HashMap<Long,Cards.Movement>();
        for(var movement:body.lines())require(movement!=null && supplied.putIfAbsent(movement.sku(),movement)==null,"商品重复");
        var items=new ArrayList<BusinessDocuments.Item>();
        for(var row:db.queryForList("SELECT sku_id,quantity FROM order_line WHERE order_id=? ORDER BY sku_id",id)){
            long sku=number(row,"sku_id");
            var movement=supplied.remove(sku);
            items.add(new BusinessDocuments.Item(sku,Math.toIntExact(number(row,"quantity")),movement==null?List.of():movement.cards(),
                movement==null?null:movement.expiry(),null));
        }
        require(supplied.isEmpty(),"存在不属于订单的商品");
        String kind="SALE".equals(order.get("direction"))?"sales_outbound":"purchase_inbound";
        long document=docs.create(new BusinessDocuments.Draft(kind,id,null,null,null,Cards.today(),null,"",items,null),actor).get("id");
        docs.transition(document,new BusinessDocuments.Transition("submit",0),actor);
        docs.transition(document,new BusinessDocuments.Transition("post",1),actor);
    }
}
