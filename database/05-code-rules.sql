-- Run once after 03-prototype.sql with the application stopped.
USE yunshu;
CREATE TABLE code_rule (
    rule_key VARCHAR(32) PRIMARY KEY,
    display_order TINYINT UNSIGNED NOT NULL UNIQUE,
    label VARCHAR(32) NOT NULL,
    prefix VARCHAR(8) NOT NULL UNIQUE,
    date_policy ENUM('OPTIONAL','ALWAYS','NEVER') NOT NULL,
    include_date BOOLEAN NOT NULL,
    serial_length TINYINT UNSIGNED NOT NULL DEFAULT 3,
    initial_value INT UNSIGNED NOT NULL DEFAULT 1,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CHECK (date_policy='OPTIONAL' OR include_date=(date_policy='ALWAYS')),
    CHECK (serial_length BETWEEN 1 AND 5 AND initial_value < POW(10,serial_length))
);
INSERT INTO code_rule(rule_key,display_order,label,prefix,date_policy,include_date) VALUES
 ('goods',1,'商品编码','DU','OPTIONAL',TRUE),
 ('customer',2,'客户编码','CU','OPTIONAL',TRUE),
 ('supplier',3,'供应商编码','SU','OPTIONAL',TRUE),
 ('unit',4,'计量单位编码','UNIT','NEVER',FALSE),
 ('warehouse',5,'仓库编码','WH','NEVER',FALSE),
 ('operator',6,'运营商编码','OP','NEVER',FALSE),
 ('SALE',7,'销售订单编码','SO','ALWAYS',TRUE),
 ('PURCHASE',8,'采购订单编码','PO','ALWAYS',TRUE),
 ('sales_return',9,'销售退换货编码','SR','ALWAYS',TRUE),
 ('purchase_return',10,'采购退换货编码','PR','ALWAYS',TRUE),
 ('purchase_inbound',11,'采购入库单编码','GR','ALWAYS',TRUE),
 ('other_inbound',12,'其它入库单编码','OR','ALWAYS',TRUE),
 ('sales_outbound',13,'销售出库单编码','DN','ALWAYS',TRUE),
 ('other_outbound',14,'其它出库单编码','OD','ALWAYS',TRUE),
 ('receipt',15,'收款单号','CN','ALWAYS',TRUE),
 ('payment',16,'付款单号','PY','ALWAYS',TRUE);

CREATE TABLE code_sequence (
    rule_key VARCHAR(32) NOT NULL,
    date_part VARCHAR(6) NOT NULL,
    last_serial INT UNSIGNED NOT NULL,
    PRIMARY KEY(rule_key,date_part),
    FOREIGN KEY(rule_key) REFERENCES code_rule(rule_key)
);
ALTER TABLE party ADD code VARCHAR(40) UNIQUE;
ALTER TABLE erp_order ADD order_code VARCHAR(40) UNIQUE;
ALTER TABLE business_document ADD doc_code VARCHAR(40) UNIQUE;

-- Freeze the numbers already displayed by the previous version, including long IDs.
UPDATE party SET code=CONCAT(IF(kind='CUSTOMER','CUS-','SUP-'),LPAD(id,GREATEST(5,CHAR_LENGTH(id)),'0'));
UPDATE erp_order o LEFT JOIN order_header h ON h.order_id=o.id
SET o.order_code=CONCAT(IF(o.direction='SALE','SO-','PO-'),DATE_FORMAT(COALESCE(h.business_day,DATE(o.created_at)),'%Y%m%d'),'-',LPAD(o.id,GREATEST(4,CHAR_LENGTH(o.id)),'0'));
UPDATE business_document SET doc_code=CONCAT(
    CASE kind WHEN 'sales_return' THEN 'ST' WHEN 'sales_exchange' THEN 'EX' WHEN 'purchase_return' THEN 'PR'
    WHEN 'purchase_inbound' THEN 'PI' WHEN 'other_inbound' THEN 'OI' WHEN 'sales_outbound' THEN 'OS'
    WHEN 'other_outbound' THEN 'OT' WHEN 'receipt' THEN 'RC' WHEN 'payment' THEN 'PM'
    WHEN 'sales_invoice' THEN 'INV' WHEN 'purchase_invoice' THEN 'PIN' WHEN 'transfer' THEN 'TR' WHEN 'stock_taking' THEN 'ST' END,
    '-',DATE_FORMAT(business_day,'%Y%m%d'),'-',LPAD(id,GREATEST(4,CHAR_LENGTH(id)),'0'));

-- Older installations allowed manual SKU and reference codes. Reserve the used numeric
-- range in each matching series before switching to automatic assignment.
INSERT INTO code_sequence(rule_key,date_part,last_serial)
SELECT rule_key,IF(CHAR_LENGTH(suffix)>6,LEFT(suffix,6),'') AS bucket,
       MAX(CAST(IF(CHAR_LENGTH(suffix)>6,SUBSTRING(suffix,7),suffix) AS UNSIGNED))
FROM (
    SELECT r.rule_key,r.date_policy,SUBSTRING(TRIM(m.code),CHAR_LENGTH(r.prefix)+1) AS suffix
    FROM (SELECT 'goods' AS family,code FROM sku UNION ALL SELECT family,code FROM base_reference) m
    JOIN code_rule r ON r.rule_key=m.family AND LEFT(TRIM(m.code),CHAR_LENGTH(r.prefix))=r.prefix
) old_codes
WHERE suffix REGEXP '^[0-9]{1,5}$' OR (date_policy<>'NEVER' AND suffix REGEXP '^[0-9]{7,11}$')
GROUP BY rule_key,bucket;

-- Previously exposed numeric IDs remain available to existing API consumers.
INSERT INTO portal_field(resource,field_key)
SELECT resource,'order_code' FROM portal_field
WHERE (resource='orders' AND field_key='id') OR (resource='inquiries' AND field_key='order_id');

DELIMITER $$
CREATE PROCEDURE allocate_erp_code(IN requested_rule VARCHAR(32), OUT assigned_code VARCHAR(40))
MODIFIES SQL DATA
BEGIN
    DECLARE head VARCHAR(8);
    DECLARE bucket VARCHAR(6);
    DECLARE width_digits, first_serial, serial_no INT;
    SELECT prefix,IF(include_date,DATE_FORMAT(UTC_TIMESTAMP()+INTERVAL 8 HOUR,'%y%m%d'),''),
           serial_length,initial_value
    INTO head,bucket,width_digits,first_serial
    FROM code_rule WHERE rule_key=requested_rule FOR UPDATE;
    INSERT INTO code_sequence(rule_key,date_part,last_serial) VALUES(requested_rule,bucket,first_serial)
    ON DUPLICATE KEY UPDATE last_serial=GREATEST(last_serial+1,first_serial);
    SELECT last_serial INTO serial_no FROM code_sequence
    WHERE rule_key=requested_rule AND date_part=bucket FOR UPDATE;
    IF serial_no >= POW(10,width_digits) THEN
        SIGNAL SQLSTATE '23000' SET MYSQL_ERRNO=31001, MESSAGE_TEXT='ERP serial range exhausted';
    END IF;
    SET assigned_code=CONCAT(head,bucket,LPAD(serial_no,width_digits,'0'));
END$$

CREATE TRIGGER code_new_goods BEFORE INSERT ON sku FOR EACH ROW
BEGIN
    DECLARE assigned VARCHAR(40);
    CALL allocate_erp_code('goods',assigned);
    SET NEW.code=assigned;
END$$
CREATE TRIGGER code_new_party BEFORE INSERT ON party FOR EACH ROW
BEGIN
    DECLARE assigned VARCHAR(40);
    CALL allocate_erp_code(LOWER(NEW.kind),assigned);
    SET NEW.code=assigned;
END$$
CREATE TRIGGER code_new_reference BEFORE INSERT ON base_reference FOR EACH ROW
BEGIN
    DECLARE assigned VARCHAR(40);
    IF NEW.family IN ('unit','warehouse','operator') THEN
        CALL allocate_erp_code(NEW.family,assigned);
        SET NEW.code=assigned;
    END IF;
END$$
CREATE TRIGGER code_new_order BEFORE INSERT ON erp_order FOR EACH ROW
BEGIN
    DECLARE assigned VARCHAR(40);
    CALL allocate_erp_code(NEW.direction,assigned);
    SET NEW.order_code=assigned;
END$$
CREATE TRIGGER code_new_document BEFORE INSERT ON business_document FOR EACH ROW
BEGIN
    DECLARE assigned VARCHAR(40);
    IF NEW.kind IN ('sales_return','sales_exchange','purchase_return','purchase_exchange','purchase_inbound','other_inbound',
                    'sales_outbound','other_outbound','receipt','payment') THEN
        CALL allocate_erp_code(CASE NEW.kind WHEN 'sales_exchange' THEN 'sales_return'
            WHEN 'purchase_exchange' THEN 'purchase_return' ELSE NEW.kind END,assigned);
        SET NEW.doc_code=assigned;
    END IF;
END$$
DELIMITER ;
