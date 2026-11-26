USE yunshu;
INSERT INTO party(id,name,kind) VALUES (1,'演示客户','CUSTOMER'),(2,'演示供应商','SUPPLIER');
INSERT INTO sku(id,code,name,kind,price) VALUES
 (1,'SIM-T','终端流量卡','TERMINAL',39.00),
 (2,'SIM-G','集团流量卡','GROUP',59.00),
 (3,'MOD-01','通信模组','HARDWARE',129.00);
INSERT INTO carrier_account(id,name,credential_ref) VALUES (1,'中国移动 · 演示集团','DEMO');
INSERT INTO portal_field(resource,field_key) VALUES ('orders','id'),('orders','state'),('orders','total'),
 ('cards','iccid'),('cards','activated_on'),('cards','erp_expiry'),
 ('cards','quota_kb'),('cards','used_kb'),('cards','remaining_kb');
INSERT INTO print_template(direction,title,company,show_price) VALUES ('SALE','销售订单','云枢 ERP',TRUE),('PURCHASE','采购订单','云枢 ERP',TRUE);
