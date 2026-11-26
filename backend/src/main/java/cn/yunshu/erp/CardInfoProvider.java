package cn.yunshu.erp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** 供应商卡信息查询端口。每个供应商实现一个适配器，由 SupplierRouter 路由。 */
interface CardInfoProvider {
    String adapterKey();
    Session open(SupplierAccount account);
    CardInfoResult fetch(Session session,SupplierAccount account,String iccid);
    JsonNode call(Session session,String endpoint,Map<String,String> query);
    JsonNode optional(Session session,String endpoint,Map<String,String> query,List<String> missing,String label);

    /** 一次查询会话，保存账号和令牌。 */
    record Session(SupplierAccount account,String token) {}
}
