package cn.yunshu.erp;

import java.util.Map;

/** 一条供应商接入账号。凭据本身不落库，只保存环境变量引用。 */
record SupplierAccount(long id,String name,String credentialRef,boolean enabled,String supplierType,
                       String apiCategory,String apiSubCategory,String adapterKey,String apiUrl) {
    static SupplierAccount from(Map<String,Object> row) {
        return new SupplierAccount(
            ((Number)row.get("id")).longValue(),
            String.valueOf(row.get("name")),
            String.valueOf(row.get("credential_ref")),
            Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled"))),
            value(row.get("supplier_type"),"CMCC").toUpperCase(java.util.Locale.ROOT),
            value(row.get("api_category"),"CMCC").toUpperCase(java.util.Locale.ROOT),
            value(row.get("api_sub_category"),"CMCC_CTBOSS").toUpperCase(java.util.Locale.ROOT),
            value(row.get("adapter_key"),"cmcc-ctboss"),
            value(row.get("api_url"),null));
    }
    private static String value(Object raw,String fallback) {
        if(raw==null)return fallback;
        String text=raw.toString().strip();
        return text.isEmpty()?fallback:text;
    }
}
