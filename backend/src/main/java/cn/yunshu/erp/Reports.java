package cn.yunshu.erp;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp/reports")
class Reports {
    private final JdbcTemplate db;
    Reports(JdbcTemplate db) {this.db=db;}
    @GetMapping("/{name}") Object rows(@PathVariable String name) {
        return switch(name) {
            case "sales","purchase" -> db.queryForList("""
                SELECT o.id AS order_id,o.direction,o.state,p.name AS party_name,COALESCE(h.business_day,CAST(o.created_at AS DATE)) AS business_day,
                       h.delivery_day,h.manager,h.external_code,w.name AS warehouse_name,s.code,s.name,l.quantity,l.unit_price,l.quantity*l.unit_price AS gross,x.tax_rate,x.memo
                FROM order_line l JOIN erp_order o ON o.id=l.order_id JOIN party p ON p.id=o.party_id JOIN sku s ON s.id=l.sku_id
                LEFT JOIN order_header h ON h.order_id=o.id LEFT JOIN base_reference w ON w.id=h.warehouse_id
                LEFT JOIN order_line_extra x ON x.order_id=o.id AND x.sku_id=l.sku_id WHERE o.direction=? ORDER BY o.id DESC,l.sku_id
                """,name.equals("sales")?"SALE":"PURCHASE");
            case "stock" -> db.queryForList("""
                SELECT w.id AS warehouse_id,w.name AS warehouse_name,s.id AS sku_id,s.code,s.name,s.kind,
                       i.on_hand,i.reserved,i.on_hand-i.reserved AS available FROM warehouse_stock i
                JOIN sku s ON s.id=i.sku_id JOIN base_reference w ON w.id=i.warehouse_id ORDER BY w.id,s.code
                """);
            case "inbound","outbound" -> db.queryForList("""
                SELECT d.id,d.kind,d.business_day,d.source_order,p.name AS party_name,w.name AS warehouse_name,
                       d.`lines`,d.amount,d.state FROM business_document d LEFT JOIN party p ON p.id=d.party_id
                LEFT JOIN base_reference w ON w.id=d.warehouse_id WHERE d.state='POSTED' AND d.kind IN (?,?,?) ORDER BY d.id DESC
                """,name.equals("inbound")?"purchase_inbound":"sales_outbound",name.equals("inbound")?"other_inbound":"other_outbound",
                    name.equals("inbound")?"sales_return":"purchase_return");
            case "receivable" -> db.queryForList("""
                SELECT p.id,p.name,
                  COALESCE(o.shipped,0)-COALESCE(f.returned,0) AS receivable,
                  COALESCE(f.invoiced,0) AS invoiced,COALESCE(f.paid,0) AS paid,
                  COALESCE(o.shipped,0)-COALESCE(f.returned,0)-COALESCE(f.paid,0) AS outstanding
                FROM party p LEFT JOIN (
                  SELECT orders.party_id,SUM(orders.amount) AS shipped FROM (
                    SELECT o.id,o.party_id,SUM(l.quantity*l.unit_price)-COALESCE(MAX(h.discount),0) AS amount
                    FROM erp_order o JOIN order_line l ON l.order_id=o.id LEFT JOIN order_header h ON h.order_id=o.id
                    WHERE o.direction='SALE' AND o.state='FULFILLED' GROUP BY o.id,o.party_id
                  ) orders GROUP BY orders.party_id
                ) o ON o.party_id=p.id LEFT JOIN (
                  SELECT party_id,SUM(CASE WHEN kind='sales_return' THEN amount ELSE 0 END) AS returned,
                    SUM(CASE WHEN kind='sales_invoice' THEN amount ELSE 0 END) AS invoiced,
                    SUM(CASE WHEN kind='receipt' THEN amount ELSE 0 END) AS paid
                  FROM business_document WHERE state='POSTED' GROUP BY party_id
                ) f ON f.party_id=p.id WHERE p.kind='CUSTOMER' ORDER BY p.name
                """);
            default -> throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"报表不存在");
        };
    }
}
