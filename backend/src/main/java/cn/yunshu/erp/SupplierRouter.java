package cn.yunshu.erp;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 按 adapter_key 选择供应商实现；后续新增供应商只需注册新的 CardInfoProvider。 */
@Component
class SupplierRouter {
    private final Map<String,CardInfoProvider> providers;

    SupplierRouter(List<CardInfoProvider> discovered) {
        this.providers=discovered.stream().collect(Collectors.toUnmodifiableMap(CardInfoProvider::adapterKey,Function.identity()));
    }

    CardInfoProvider forAccount(SupplierAccount account) {
        String key=account.adapterKey();
        if(key==null || key.isBlank())
            key=account.apiSubCategory()==null?"":account.apiSubCategory().toLowerCase(Locale.ROOT).replace('_','-');
        if(key.startsWith("cmcc-onelink"))key=key.replace("cmcc-onelink","cmcc-onlink");
        CardInfoProvider provider=providers.get(key);
        if(provider==null)
            throw SupplierFailure.config("SUPPLIER_ADAPTER_UNKNOWN","未配置供应商适配器："+key);
        return provider;
    }

    Set<String> adapters() { return providers.keySet(); }
}
