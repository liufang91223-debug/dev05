package cn.yunshu.erp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.List;

/** 供应商返回的卡身份信息。字段为空表示供应商没有返回，不代表本地数据被清空。 */
record CardInfoResult(String iccid,String msisdn,String imsi,String imei,LocalDate activatedOn,
                      LocalDate carrierExpiry,List<String> missing,JsonNode offerings,JsonNode apn,
                      JsonNode services,String requestId,Instant observedAt) {}
