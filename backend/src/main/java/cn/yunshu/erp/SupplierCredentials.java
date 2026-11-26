package cn.yunshu.erp;

import org.springframework.stereotype.Component;

/** 从服务端环境变量读取供应商凭据，不把密码写入数据库或接口响应。 */
@Component
class SupplierCredentials {
    record Credentials(String appId,String password) {}

    Credentials load(SupplierAccount account) {
        String prefix=account.supplierType()+"_"+account.credentialRef();
        String appId=read(prefix+"_APPID");
        String password=read(prefix+"_PASSWORD");
        if(appId==null || appId.isBlank() || password==null || password.isBlank())
            throw SupplierFailure.config("SUPPLIER_CREDENTIALS_MISSING",
                "服务端缺少供应商凭据 "+prefix+"_APPID / "+prefix+"_PASSWORD");
        if(!appId.matches("[0-9A-Za-z._-]{1,60}"))
            throw SupplierFailure.config("SUPPLIER_CREDENTIALS_INVALID","供应商凭据标识格式不正确");
        return new Credentials(appId.strip(),password);
    }

    /** 测试可覆盖；生产环境只读取进程环境变量。 */
    String read(String name) { return System.getenv(name); }
}
