package cn.yunshu.erp;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 读取供应商账号及卡所属账号，供回填和账号配置复用。 */
@Component
class SupplierAccounts {
    record CardContext(SupplierAccount account,String kind,boolean enabled) {}

    private final JdbcTemplate db;
    SupplierAccounts(JdbcTemplate db) { this.db=db; }

    SupplierAccount byId(long id) {
        var rows=db.queryForList("""
            SELECT id,name,credential_ref,enabled,supplier_type,api_category,api_sub_category,adapter_key,api_url
            FROM carrier_account WHERE id=?
            """,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到供应商账号");
        return SupplierAccount.from(rows.getFirst());
    }

    CardContext card(String iccid) {
        var rows=db.queryForList("""
            SELECT a.id,a.name,a.credential_ref,a.enabled,a.supplier_type,a.api_category,a.api_sub_category,
                   a.adapter_key,a.api_url,s.kind
            FROM sim_card c JOIN carrier_account a ON a.id=c.carrier_account_id JOIN sku s ON s.id=c.sku_id
            WHERE c.iccid=?
            """,iccid);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到该卡或供应商账号");
        Map<String,Object> row=rows.getFirst();
        return new CardContext(SupplierAccount.from(row),String.valueOf(row.get("kind")),
            Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled"))));
    }
}
