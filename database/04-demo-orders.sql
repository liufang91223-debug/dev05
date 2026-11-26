-- Optional local examples. Run after the demo accounts have been created.
-- These two drafts do not reserve inventory or post accounting entries.
USE yunshu;
START TRANSACTION;
SET @demo_warehouse = (SELECT id FROM base_reference WHERE family='warehouse' AND enabled=TRUE ORDER BY CASE WHEN code='WH-001' THEN 0 ELSE 1 END,id LIMIT 1);
SET @demo_sim = (SELECT id FROM sku WHERE code='SIM-T');
SET @demo_device = (SELECT id FROM sku WHERE code='MOD-01');
SET @demo_customer = (SELECT id FROM party WHERE kind='CUSTOMER' ORDER BY id LIMIT 1);
SET @demo_vendor = (SELECT id FROM party WHERE kind='SUPPLIER' ORDER BY id LIMIT 1);
SET @demo_author = (SELECT id FROM account WHERE role='ADMIN' ORDER BY id LIMIT 1);
SET @demo_account = (SELECT id FROM account WHERE role='ERP' ORDER BY id LIMIT 1);
SET @demo_po = EXISTS(SELECT 1 FROM order_header WHERE external_code='DEMO-PO-001');
INSERT INTO erp_order(direction,party_id,creator_id)
SELECT 'PURCHASE',@demo_vendor,@demo_account FROM DUAL
WHERE @demo_po=0 AND @demo_vendor IS NOT NULL AND @demo_account IS NOT NULL AND @demo_sim IS NOT NULL AND @demo_warehouse IS NOT NULL;
SET @created_po=IF(ROW_COUNT()=1,LAST_INSERT_ID(),NULL);
INSERT INTO order_header(order_id,business_day,delivery_day,manager,warehouse_id,external_code,notes)
SELECT @created_po,CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 3 DAY),'buyer',@demo_warehouse,'DEMO-PO-001','演示采购，入库时填写实际演示卡号'
WHERE @created_po IS NOT NULL;
INSERT INTO order_line(order_id,sku_id,quantity,unit_price) SELECT @created_po,@demo_sim,2,18.00 WHERE @created_po IS NOT NULL;
INSERT INTO purchase_line_account(order_id,sku_id,carrier_account_id)
SELECT @created_po,@demo_sim,c.id
FROM carrier_account c
WHERE c.enabled=TRUE AND @created_po IS NOT NULL
ORDER BY c.id LIMIT 1;

SET @demo_so = EXISTS(SELECT 1 FROM order_header WHERE external_code='DEMO-SO-001');
INSERT INTO erp_order(direction,party_id,creator_id)
SELECT 'SALE',@demo_customer,@demo_author FROM DUAL
WHERE @demo_so=0 AND @demo_customer IS NOT NULL AND @demo_author IS NOT NULL AND @demo_device IS NOT NULL AND @demo_warehouse IS NOT NULL;
SET @created_so=IF(ROW_COUNT()=1,LAST_INSERT_ID(),NULL);
INSERT INTO order_header(order_id,business_day,delivery_day,manager,warehouse_id,external_code,notes)
SELECT @created_so,CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 7 DAY),'admin',@demo_warehouse,'DEMO-SO-001','演示设备订单，按数量管理'
WHERE @created_so IS NOT NULL;
INSERT INTO order_line(order_id,sku_id,quantity,unit_price) SELECT @created_so,@demo_device,3,129.00 WHERE @created_so IS NOT NULL;
COMMIT;
