-- Optional local examples. Run after the demo accounts have been created.
-- These two drafts do not reserve inventory or post accounting entries.
USE yunshu;
START TRANSACTION;
SET @demo_author = (SELECT id FROM account WHERE username='admin' AND role='ADMIN');
SET @demo_customer = (SELECT id FROM party WHERE id=1 AND name='演示客户' AND kind='CUSTOMER');
SET @demo_vendor = (SELECT id FROM party WHERE id=2 AND name='演示供应商' AND kind='SUPPLIER');
SET @demo_sim = (SELECT id FROM sku WHERE code='SIM-T');
SET @demo_device = (SELECT id FROM sku WHERE code='MOD-01');
SET @demo_po = (SELECT MIN(order_id) FROM order_header WHERE external_code='DEMO-PO-001');
INSERT INTO erp_order(direction,party_id,creator_id)
SELECT 'PURCHASE',@demo_vendor,@demo_author FROM DUAL
WHERE @demo_po IS NULL AND @demo_vendor IS NOT NULL AND @demo_author IS NOT NULL AND @demo_sim IS NOT NULL;
SET @created_po=IF(ROW_COUNT()=1,LAST_INSERT_ID(),NULL);
INSERT INTO order_header(order_id,business_day,delivery_day,manager,warehouse_id,external_code,notes)
SELECT @created_po,CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 3 DAY),'buyer',1,'DEMO-PO-001','演示采购，入库时填写实际演示卡号'
WHERE @created_po IS NOT NULL;
INSERT INTO order_line SELECT @created_po,@demo_sim,2,18.00 WHERE @created_po IS NOT NULL;
INSERT INTO purchase_line_account SELECT @created_po,@demo_sim,id FROM carrier_account WHERE id=1 AND @created_po IS NOT NULL;

SET @demo_so = (SELECT MIN(order_id) FROM order_header WHERE external_code='DEMO-SO-001');
INSERT INTO erp_order(direction,party_id,creator_id)
SELECT 'SALE',@demo_customer,@demo_author FROM DUAL
WHERE @demo_so IS NULL AND @demo_customer IS NOT NULL AND @demo_author IS NOT NULL AND @demo_device IS NOT NULL;
SET @created_so=IF(ROW_COUNT()=1,LAST_INSERT_ID(),NULL);
INSERT INTO order_header(order_id,business_day,delivery_day,manager,warehouse_id,external_code,notes)
SELECT @created_so,CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 7 DAY),'admin',1,'DEMO-SO-001','演示设备订单，按数量管理'
WHERE @created_so IS NOT NULL;
INSERT INTO order_line SELECT @created_so,@demo_device,3,129.00 WHERE @created_so IS NOT NULL;
COMMIT;
