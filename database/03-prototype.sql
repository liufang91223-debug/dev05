-- Fresh-install only: run after database/01-schema.sql and database/02-demo.sql.
-- Historical fresh-install script. For the merged release, use migrate.py for
-- both installation and upgrade; do not rerun this file on a business database.
USE yunshu;

CREATE TABLE base_reference (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 family VARCHAR(24) NOT NULL,
 code VARCHAR(40) NOT NULL,
 name VARCHAR(120) NOT NULL,
 description VARCHAR(250) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(family,code)
);
INSERT INTO base_reference(family,code,name) VALUES
 ('warehouse','WH-001','主仓库'),('unit','UNIT-001','张'),
 ('unit','UNIT-002','台'),('settlement','SET-001','月结 30 天'),
 ('category','CAT-001','物联网卡'),('category','CAT-002','通信设备'),
 ('operator','OP-001','中国移动');
SET @main_warehouse = (
 SELECT id FROM base_reference
 WHERE family='warehouse' AND enabled=TRUE
 ORDER BY CASE WHEN code='WH-001' THEN 0 ELSE 1 END,id LIMIT 1
);

CREATE TABLE master_profile (
 family VARCHAR(20) NOT NULL,
 entity_id BIGINT NOT NULL,
 details TEXT NOT NULL,
 PRIMARY KEY(family,entity_id)
);
CREATE TABLE order_header (
 order_id BIGINT PRIMARY KEY,
 business_day DATE NOT NULL,
 delivery_day DATE,
 manager VARCHAR(100) NOT NULL DEFAULT '',
 warehouse_id BIGINT NOT NULL,
 settlement VARCHAR(120) NOT NULL DEFAULT '',
 external_code VARCHAR(90) NOT NULL DEFAULT '',
 notes VARCHAR(250) NOT NULL DEFAULT '',
 discount DECIMAL(16,2) NOT NULL DEFAULT 0,
 FOREIGN KEY(order_id) REFERENCES erp_order(id),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id)
);
CREATE TABLE order_line_extra (
 order_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 tax_rate DECIMAL(5,2) NOT NULL DEFAULT 13,
 internal_note VARCHAR(200) NOT NULL DEFAULT '',
 memo VARCHAR(200) NOT NULL DEFAULT '',
 PRIMARY KEY(order_id,sku_id),
 FOREIGN KEY(order_id,sku_id) REFERENCES order_line(order_id,sku_id) ON DELETE CASCADE
);
CREATE TABLE warehouse_stock (
 warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 on_hand INT NOT NULL DEFAULT 0, reserved INT NOT NULL DEFAULT 0,
 PRIMARY KEY(warehouse_id,sku_id),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id),
 FOREIGN KEY(sku_id) REFERENCES sku(id),
 CHECK(on_hand>=0 AND reserved>=0 AND on_hand>=reserved)
);
INSERT INTO warehouse_stock(warehouse_id,sku_id,on_hand,reserved) SELECT @main_warehouse,sku_id,on_hand,reserved FROM inventory;
CREATE TABLE card_location (
 iccid VARCHAR(32) PRIMARY KEY, warehouse_id BIGINT NOT NULL,
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id)
);
INSERT INTO card_location(iccid,warehouse_id) SELECT iccid,@main_warehouse FROM sim_card;
CREATE TABLE business_document (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 kind VARCHAR(30) NOT NULL,
 source_order BIGINT,
 source_doc BIGINT,
 party_id BIGINT,
 warehouse_id BIGINT,
 target_warehouse BIGINT,
 business_day DATE NOT NULL,
 amount DECIMAL(16,2) NOT NULL DEFAULT 0,
 memo VARCHAR(250) NOT NULL DEFAULT '',
 `lines` MEDIUMTEXT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 revision INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL,
 posted_by BIGINT,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 posted_at TIMESTAMP NULL,
 FOREIGN KEY(source_order) REFERENCES erp_order(id),
 FOREIGN KEY(source_doc) REFERENCES business_document(id),
 FOREIGN KEY(party_id) REFERENCES party(id),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id),
 FOREIGN KEY(target_warehouse) REFERENCES base_reference(id),
 FOREIGN KEY(created_by) REFERENCES account(id),
 FOREIGN KEY(posted_by) REFERENCES account(id),
 INDEX idx_document_list(kind,business_day,id),
 INDEX idx_document_source(source_order,kind,state)
);

CREATE TABLE sim_plan (
 iccid VARCHAR(32) PRIMARY KEY,
 plan_name VARCHAR(100) NOT NULL DEFAULT '',
 cycle_months INT NOT NULL DEFAULT 1 CHECK(cycle_months BETWEEN 1 AND 24),
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);
CREATE TABLE cycle_meter (
 iccid VARCHAR(32) NOT NULL, starts_on DATE NOT NULL, month_start DATE NOT NULL,
 raw_baseline DECIMAL(19,3) NOT NULL, confirmed_carry DECIMAL(19,3) NOT NULL,
 PRIMARY KEY(iccid,starts_on,month_start),
 FOREIGN KEY(iccid,starts_on) REFERENCES usage_cycle(iccid,starts_on) ON DELETE CASCADE
);
CREATE TABLE carrier_services (
 iccid VARCHAR(32) PRIMARY KEY, plan_name VARCHAR(500) NOT NULL DEFAULT '',
 apn_details TEXT, communication_details TEXT,
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);
CREATE TABLE purchase_line_account (
 order_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,carrier_account_id BIGINT NOT NULL,
 PRIMARY KEY(order_id,sku_id),
 FOREIGN KEY(order_id,sku_id) REFERENCES order_line(order_id,sku_id) ON DELETE CASCADE,
 FOREIGN KEY(carrier_account_id) REFERENCES carrier_account(id)
);

ALTER TABLE portal_field MODIFY resource ENUM('orders','cards','inquiries') NOT NULL;
INSERT INTO portal_field(resource,field_key) VALUES ('inquiries','id'),('inquiries','name'),('inquiries','quantity'),('inquiries','state'),('inquiries','order_id');
CREATE VIEW portal_catalog AS
SELECT s.id,COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.public_name')),''),
       CASE WHEN s.kind='HARDWARE' THEN s.name ELSE CONCAT('物联网卡 ',s.id) END) AS name,
       s.price,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.enabled')),'true') <> 'false' AS enabled
FROM sku s LEFT JOIN master_profile p ON p.family='goods' AND p.entity_id=s.id;

CREATE TABLE carrier_sync_queue (
 iccid VARCHAR(32) PRIMARY KEY,
 requested_by BIGINT NOT NULL,
 requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 next_attempt TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token CHAR(36), attempts INT NOT NULL DEFAULT 0,
 last_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 INDEX idx_next_sync(next_attempt),
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid),
 FOREIGN KEY(requested_by) REFERENCES account(id)
);

-- Preserve legacy orders and cards created before these pages were introduced.
INSERT INTO order_header(order_id,business_day,warehouse_id)
SELECT o.id,DATE(o.created_at),@main_warehouse FROM erp_order o LEFT JOIN order_header h ON h.order_id=o.id
WHERE h.order_id IS NULL;
INSERT INTO order_line_extra(order_id,sku_id)
SELECT l.order_id,l.sku_id FROM order_line l
LEFT JOIN order_line_extra x ON x.order_id=l.order_id AND x.sku_id=l.sku_id WHERE x.order_id IS NULL;
INSERT INTO sim_plan(iccid)
SELECT c.iccid FROM sim_card c LEFT JOIN sim_plan p ON p.iccid=c.iccid WHERE p.iccid IS NULL;
INSERT INTO carrier_sync_queue(iccid,requested_by)
SELECT c.iccid,o.creator_id FROM sim_card c JOIN erp_order o ON o.id=c.purchase_order_id
LEFT JOIN carrier_sync_queue q ON q.iccid=c.iccid WHERE q.iccid IS NULL;
ALTER TABLE print_template
 ADD show_code BOOLEAN NOT NULL DEFAULT TRUE,
 ADD show_delivery BOOLEAN NOT NULL DEFAULT TRUE,
 ADD footer VARCHAR(250) NOT NULL DEFAULT '';
