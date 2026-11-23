package cn.yunshu.erp;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Documents {
    private final JdbcTemplate db;
    private final Orders orders;
    Documents(JdbcTemplate db,Orders orders) { this.db=db; this.orders=orders; }

    record Template(String title,String company,boolean showPrice,Boolean showCode,Boolean showDelivery,String footer) {}
    @GetMapping("/admin/templates") Object templates() {
        return db.queryForList("SELECT * FROM print_template ORDER BY direction");
    }
    @PutMapping("/admin/templates/{direction}") void template(@PathVariable String direction,@RequestBody Template body) {
        Orders.require(java.util.Set.of("SALE","PURCHASE").contains(direction) && body.title()!=null && !body.title().isBlank()
            && body.title().length()<=100 && body.company()!=null && body.company().length()<=120,"模板参数无效");
        String footer=body.footer()==null?"":body.footer().trim();
        Orders.require(footer.length()<=250,"页尾最多 250 字");
        boolean codes=!Boolean.FALSE.equals(body.showCode()),delivery=!Boolean.FALSE.equals(body.showDelivery());
        db.update("""
            INSERT INTO print_template(direction,title,company,show_price,show_code,show_delivery,footer) VALUES(?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE title=?,company=?,show_price=?,show_code=?,show_delivery=?,footer=?
            """,direction,body.title(),body.company(),body.showPrice(),codes,delivery,footer,
            body.title(),body.company(),body.showPrice(),codes,delivery,footer);
    }

    @GetMapping("/erp/orders/{id}/pdf") ResponseEntity<byte[]> print(@PathVariable long id) throws Exception {
        var order=orders.one("SELECT o.*,p.name AS party_name,COALESCE(h.business_day,DATE(o.created_at)) AS business_day,h.delivery_day,w.name AS warehouse_name FROM erp_order o JOIN party p ON p.id=o.party_id LEFT JOIN order_header h ON h.order_id=o.id LEFT JOIN base_reference w ON w.id=h.warehouse_id WHERE o.id=?",id);
        String direction=order.get("direction").toString();
        var template=db.queryForList("SELECT * FROM print_template WHERE direction=?",direction).stream().findFirst()
            .orElse(Map.of("title",direction.equals("SALE")?"销售订单":"采购订单","company","","show_price",true));
        boolean showPrice=shown(template,"show_price"),showCode=shown(template,"show_code");
        String number=(direction.equals("SALE")?"SO":"PO")+"-"+order.get("business_day").toString().replace("-","")+"-"+String.format("%04d",id);
        String path=System.getenv("ERP_PDF_FONT");
        if (path==null || path.isBlank()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "请设置 ERP_PDF_FONT 指向支持中文的 OpenType 字体");
        var font=new Font(BaseFont.createFont(path,BaseFont.IDENTITY_H,BaseFont.EMBEDDED),11);
        var output=new ByteArrayOutputStream();
        var pdf=new Document(PageSize.A4);
        PdfWriter.getInstance(pdf,output);
        pdf.open();
        try {
            pdf.add(new Paragraph(template.get("company").toString(),font));
            pdf.add(new Paragraph(template.get("title")+"  "+number,font));
            pdf.add(new Paragraph("往来单位："+order.get("party_name")+"    状态："+status(order.get("state").toString()),font));
            pdf.add(new Paragraph("单据日期："+order.get("business_day"),font));
            if(shown(template,"show_delivery")){
                pdf.add(new Paragraph("交货日期："+java.util.Objects.toString(order.get("delivery_day"),"—")+
                    "    仓库："+java.util.Objects.toString(order.get("warehouse_name"),"—"),font));
            }
            var table=new PdfPTable((showPrice?4:2)+(showCode?1:0));
            table.setWidthPercentage(100);
            table.setSpacingBefore(14);
            if(showCode)table.addCell(new Phrase("商品编码",font));
            for (String heading : showPrice?new String[]{"商品","数量","单价","小计"}:new String[]{"商品","数量"})
                table.addCell(new Phrase(heading,font));
            table.setHeaderRows(1);
            for (var line:db.queryForList("""
                SELECT s.code,s.name,l.quantity,l.unit_price,l.unit_price*l.quantity AS amount
                FROM order_line l JOIN sku s ON s.id=l.sku_id WHERE l.order_id=? ORDER BY l.sku_id
                """,id)) {
                if(showCode)table.addCell(new Phrase(line.get("code").toString(),font));
                table.addCell(new Phrase(line.get("name").toString(),font));
                table.addCell(new Phrase(line.get("quantity").toString(),font));
                if (showPrice) {
                    table.addCell(new Phrase(line.get("unit_price").toString(),font));
                    table.addCell(new Phrase(line.get("amount").toString(),font));
                }
            }
            pdf.add(table);
            if(showPrice) {
                var gross=db.queryForObject("SELECT SUM(quantity*unit_price) FROM order_line WHERE order_id=?",java.math.BigDecimal.class,id);
                var discounts=db.queryForList("SELECT discount FROM order_header WHERE order_id=?",java.math.BigDecimal.class,id);
                var discount=discounts.isEmpty()?java.math.BigDecimal.ZERO:discounts.getFirst();
                pdf.add(new Paragraph("商品金额 "+gross+"   优惠金额 "+discount+"   含税合计 "+gross.subtract(discount),font));
            }
            String footer=java.util.Objects.toString(template.get("footer"),"");
            if(!footer.isBlank())pdf.add(new Paragraph(footer,font));
        } finally { pdf.close(); }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+number+".pdf")
            .cacheControl(CacheControl.noStore()).body(output.toByteArray());
    }

    private boolean shown(Map<String,Object> template,String key) {
        Object value=template.getOrDefault(key,true);
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue()!=0);
    }

    private String status(String value) {
        return switch(value) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待审批";
            case "APPROVED" -> "已审核";
            case "FULFILLED" -> "已出入库";
            default -> value;
        };
    }
}
