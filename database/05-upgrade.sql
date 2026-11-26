-- 云枢 ERP schema upgrade for an existing database.
-- Use this after database/01-schema.sql and database/02-demo.sql when the
-- database may already contain an earlier version of the prototype tables.
-- Legacy schemas only. Use migrate.py for the merged release. Never run this
-- normalizer after 05-code-rules.sql: it rebuilds the old JSON portal view.
USE yunshu;

DROP TEMPORARY TABLE IF EXISTS _legacy_upgrade_guard;
CREATE TEMPORARY TABLE _legacy_upgrade_guard (
  legacy_only TINYINT NOT NULL,
  CONSTRAINT legacy_upgrade_use_migrate_py CHECK (legacy_only=1)
);
INSERT INTO _legacy_upgrade_guard
SELECT NOT EXISTS(SELECT 1 FROM information_schema.TABLES
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='code_rule');
DROP TEMPORARY TABLE _legacy_upgrade_guard;

SET @schema_name = DATABASE();

-- 1. Widen legacy INT keys before creating/recreating foreign keys. MySQL
--    rejects a BIGINT child column that references an INT parent column, so a
--    straight upgrade of an older INT-based schema would stop at the first
--    prototype table. Temporarily remove all local foreign keys, widen the
--    known ID columns, then restore the original constraints unchanged.
DROP PROCEDURE IF EXISTS _yunshu_widen_column;
DROP PROCEDURE IF EXISTS _yunshu_widen_legacy_ids;
DELIMITER $$
CREATE PROCEDURE _yunshu_widen_column(IN p_table VARCHAR(64),IN p_column VARCHAR(64),IN p_definition VARCHAR(120))
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=p_table AND COLUMN_NAME=p_column
      AND DATA_TYPE<>'bigint'
  ) THEN
    SET @widen_sql=CONCAT('ALTER TABLE `',p_table,'` MODIFY COLUMN `',p_column,'` ',p_definition);
    PREPARE widen_stmt FROM @widen_sql;
    EXECUTE widen_stmt;
    DEALLOCATE PREPARE widen_stmt;
  END IF;
END$$
CREATE PROCEDURE _yunshu_widen_legacy_ids()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_table VARCHAR(64);
  DECLARE v_constraint VARCHAR(64);
  DECLARE v_ref_table VARCHAR(64);
  DECLARE v_columns TEXT;
  DECLARE v_ref_columns TEXT;
  DECLARE v_update_rule VARCHAR(64);
  DECLARE v_delete_rule VARCHAR(64);
  DECLARE fk_drop CURSOR FOR
    SELECT table_name,constraint_name FROM _yunshu_fk_backup;
  DECLARE fk_add CURSOR FOR
    SELECT table_name,constraint_name,referenced_table_name,child_columns,parent_columns,update_rule,delete_rule
    FROM _yunshu_fk_backup;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;

  SET @widen_old_fk_checks=@@FOREIGN_KEY_CHECKS;
  SET SESSION group_concat_max_len=1000000;
  SET FOREIGN_KEY_CHECKS=0;
  DROP TEMPORARY TABLE IF EXISTS _yunshu_fk_backup;
  CREATE TEMPORARY TABLE _yunshu_fk_backup (
    table_name VARCHAR(64) NOT NULL,
    constraint_name VARCHAR(64) NOT NULL,
    referenced_table_name VARCHAR(64) NOT NULL,
    child_columns TEXT NOT NULL,
    parent_columns TEXT NOT NULL,
    update_rule VARCHAR(64) NOT NULL,
    delete_rule VARCHAR(64) NOT NULL,
    PRIMARY KEY(table_name,constraint_name)
  );
  INSERT INTO _yunshu_fk_backup
  SELECT k.TABLE_NAME,k.CONSTRAINT_NAME,k.REFERENCED_TABLE_NAME,
         GROUP_CONCAT(CONCAT('`',k.COLUMN_NAME,'`') ORDER BY k.ORDINAL_POSITION),
         GROUP_CONCAT(CONCAT('`',k.REFERENCED_COLUMN_NAME,'`') ORDER BY k.ORDINAL_POSITION),
         r.UPDATE_RULE,r.DELETE_RULE
  FROM information_schema.KEY_COLUMN_USAGE k
  JOIN information_schema.REFERENTIAL_CONSTRAINTS r
    ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
   AND r.TABLE_NAME=k.TABLE_NAME
  WHERE k.TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL
  GROUP BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.REFERENCED_TABLE_NAME,r.UPDATE_RULE,r.DELETE_RULE;

  OPEN fk_drop;
  drop_loop: LOOP
    FETCH fk_drop INTO v_table,v_constraint;
    IF done=1 THEN LEAVE drop_loop; END IF;
    SET @widen_sql=CONCAT('ALTER TABLE `',v_table,'` DROP FOREIGN KEY `',v_constraint,'`');
    PREPARE widen_stmt FROM @widen_sql;
    EXECUTE widen_stmt;
    DEALLOCATE PREPARE widen_stmt;
  END LOOP;
  CLOSE fk_drop;
  SET done=0;

  CALL _yunshu_widen_column('party','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('account','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('account','party_id','BIGINT NULL');
  CALL _yunshu_widen_column('sku','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('carrier_account','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('erp_order','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('erp_order','party_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('erp_order','inquiry_id','BIGINT NULL');
  CALL _yunshu_widen_column('erp_order','creator_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_line','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_line','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('approval_step','account_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('inquiry','customer_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('inquiry','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('approval_assignment','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('approval_assignment','account_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('approval_log','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('approval_log','actor_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('inventory','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('sim_card','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('sim_card','carrier_account_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('sim_card','customer_id','BIGINT NULL');
  CALL _yunshu_widen_column('sim_card','purchase_order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('sim_card','sale_order_id','BIGINT NULL');
  CALL _yunshu_widen_column('usage_cycle','override_by','BIGINT NULL');
  CALL _yunshu_widen_column('sim_event','actor_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('base_reference','id','BIGINT NOT NULL AUTO_INCREMENT');
  CALL _yunshu_widen_column('master_profile','entity_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_header','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_header','warehouse_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_line_extra','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('order_line_extra','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('warehouse_stock','warehouse_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('warehouse_stock','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('card_location','warehouse_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('business_document','source_order','BIGINT NULL');
  CALL _yunshu_widen_column('business_document','source_doc','BIGINT NULL');
  CALL _yunshu_widen_column('business_document','party_id','BIGINT NULL');
  CALL _yunshu_widen_column('business_document','warehouse_id','BIGINT NULL');
  CALL _yunshu_widen_column('business_document','target_warehouse','BIGINT NULL');
  CALL _yunshu_widen_column('business_document','created_by','BIGINT NOT NULL');
  CALL _yunshu_widen_column('business_document','posted_by','BIGINT NULL');
  CALL _yunshu_widen_column('purchase_line_account','order_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('purchase_line_account','sku_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('purchase_line_account','carrier_account_id','BIGINT NOT NULL');
  CALL _yunshu_widen_column('carrier_sync_queue','requested_by','BIGINT NOT NULL');

  OPEN fk_add;
  add_loop: LOOP
    FETCH fk_add INTO v_table,v_constraint,v_ref_table,v_columns,v_ref_columns,v_update_rule,v_delete_rule;
    IF done=1 THEN LEAVE add_loop; END IF;
    SET @widen_sql=CONCAT('ALTER TABLE `',v_table,'` ADD CONSTRAINT `',v_constraint,
      '` FOREIGN KEY (',v_columns,') REFERENCES `',v_ref_table,'` (',v_ref_columns,
      ') ON DELETE ',v_delete_rule,' ON UPDATE ',v_update_rule);
    PREPARE widen_stmt FROM @widen_sql;
    EXECUTE widen_stmt;
    DEALLOCATE PREPARE widen_stmt;
  END LOOP;
  CLOSE fk_add;

  DROP TEMPORARY TABLE _yunshu_fk_backup;
  SET FOREIGN_KEY_CHECKS=@widen_old_fk_checks;
END$$
DELIMITER ;
-- Only drop and recreate foreign keys when at least one known legacy key
-- column still needs widening. A database that has already been upgraded
-- therefore skips this blocking DDL on subsequent runs.
SET @legacy_id_count = (
 SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE<>'bigint' AND (
   (TABLE_NAME='party' AND COLUMN_NAME='id') OR
   (TABLE_NAME='account' AND COLUMN_NAME IN ('id','party_id')) OR
   (TABLE_NAME='sku' AND COLUMN_NAME='id') OR
   (TABLE_NAME='carrier_account' AND COLUMN_NAME='id') OR
   (TABLE_NAME='erp_order' AND COLUMN_NAME IN ('id','party_id','inquiry_id','creator_id')) OR
   (TABLE_NAME='order_line' AND COLUMN_NAME IN ('order_id','sku_id')) OR
   (TABLE_NAME='approval_step' AND COLUMN_NAME='account_id') OR
   (TABLE_NAME='inquiry' AND COLUMN_NAME IN ('customer_id','sku_id')) OR
   (TABLE_NAME='approval_assignment' AND COLUMN_NAME IN ('order_id','account_id')) OR
   (TABLE_NAME='approval_log' AND COLUMN_NAME IN ('order_id','actor_id')) OR
   (TABLE_NAME='inventory' AND COLUMN_NAME='sku_id') OR
   (TABLE_NAME='sim_card' AND COLUMN_NAME IN ('sku_id','carrier_account_id','customer_id','purchase_order_id','sale_order_id')) OR
   (TABLE_NAME='usage_cycle' AND COLUMN_NAME='override_by') OR
   (TABLE_NAME='sim_event' AND COLUMN_NAME='actor_id') OR
   (TABLE_NAME='base_reference' AND COLUMN_NAME='id') OR
   (TABLE_NAME='master_profile' AND COLUMN_NAME='entity_id') OR
   (TABLE_NAME='order_header' AND COLUMN_NAME IN ('order_id','warehouse_id')) OR
   (TABLE_NAME='order_line_extra' AND COLUMN_NAME IN ('order_id','sku_id')) OR
   (TABLE_NAME='warehouse_stock' AND COLUMN_NAME IN ('warehouse_id','sku_id')) OR
   (TABLE_NAME='card_location' AND COLUMN_NAME='warehouse_id') OR
   (TABLE_NAME='business_document' AND COLUMN_NAME IN ('source_order','source_doc','party_id','warehouse_id','target_warehouse','created_by','posted_by')) OR
   (TABLE_NAME='purchase_line_account' AND COLUMN_NAME IN ('order_id','sku_id','carrier_account_id')) OR
   (TABLE_NAME='carrier_sync_queue' AND COLUMN_NAME='requested_by')
 )
);
SET @widen_call = IF(@legacy_id_count>0,'CALL _yunshu_widen_legacy_ids()','DO 0');
PREPARE stmt FROM @widen_call; EXECUTE stmt; DEALLOCATE PREPARE stmt;
DROP PROCEDURE _yunshu_widen_legacy_ids;
DROP PROCEDURE _yunshu_widen_column;

-- 2. Ensure the original business tables exist too. This keeps the upgrade
--    usable when an older database only had the tables from an early 01-schema
--    snapshot. Definitions here always use BIGINT keys.
CREATE TABLE IF NOT EXISTS party (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(120) NOT NULL,
 kind ENUM('CUSTOMER','SUPPLIER') NOT NULL
);

CREATE TABLE IF NOT EXISTS account (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role ENUM('ADMIN','ERP','CUSTOMER') NOT NULL,
 party_id BIGINT,
 FOREIGN KEY (party_id) REFERENCES party(id)
);

CREATE TABLE IF NOT EXISTS sku (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 code VARCHAR(40) NOT NULL UNIQUE,
 name VARCHAR(120) NOT NULL,
 kind ENUM('HARDWARE','GROUP','TERMINAL') NOT NULL,
 price DECIMAL(16,2) NOT NULL CHECK (price >= 0)
);

CREATE TABLE IF NOT EXISTS carrier_account (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(120) NOT NULL UNIQUE,
 credential_ref VARCHAR(40) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS approval_step (
 direction ENUM('SALE','PURCHASE') NOT NULL,
 step_no INT NOT NULL,
 account_id BIGINT NOT NULL,
 PRIMARY KEY(direction, step_no, account_id),
 FOREIGN KEY (account_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS inquiry (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 customer_id BIGINT NOT NULL,
 sku_id BIGINT NOT NULL,
 quantity INT NOT NULL CHECK (quantity > 0),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (customer_id) REFERENCES party(id),
 FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE IF NOT EXISTS erp_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 direction ENUM('SALE','PURCHASE') NOT NULL,
 party_id BIGINT NOT NULL,
 inquiry_id BIGINT UNIQUE,
 state ENUM('DRAFT','PENDING','APPROVED','FULFILLED') NOT NULL DEFAULT 'DRAFT',
 creator_id BIGINT NOT NULL,
 round_no INT NOT NULL DEFAULT 0,
 current_step INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (party_id) REFERENCES party(id),
 FOREIGN KEY (inquiry_id) REFERENCES inquiry(id),
 FOREIGN KEY (creator_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS order_line (
 order_id BIGINT NOT NULL,
 sku_id BIGINT NOT NULL,
 quantity INT NOT NULL CHECK (quantity > 0),
 unit_price DECIMAL(16,2) NOT NULL CHECK (unit_price >= 0),
 PRIMARY KEY(order_id,sku_id),
 FOREIGN KEY (order_id) REFERENCES erp_order(id),
 FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE IF NOT EXISTS approval_assignment (
 order_id BIGINT NOT NULL,
 round_no INT NOT NULL,
 step_no INT NOT NULL,
 account_id BIGINT NOT NULL,
 PRIMARY KEY(order_id,round_no,step_no,account_id),
 FOREIGN KEY (order_id) REFERENCES erp_order(id),
 FOREIGN KEY (account_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS approval_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 order_id BIGINT NOT NULL,
 round_no INT NOT NULL,
 step_no INT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(30) NOT NULL,
 reason VARCHAR(300),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (order_id) REFERENCES erp_order(id),
 FOREIGN KEY (actor_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS inventory (
 sku_id BIGINT PRIMARY KEY,
 on_hand INT NOT NULL DEFAULT 0 CHECK(on_hand >= 0),
 reserved INT NOT NULL DEFAULT 0,
 CHECK(reserved >= 0 AND reserved <= on_hand),
 FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE IF NOT EXISTS sim_card (
 iccid VARCHAR(32) PRIMARY KEY,
 sku_id BIGINT NOT NULL,
 carrier_account_id BIGINT NOT NULL,
 customer_id BIGINT,
 purchase_order_id BIGINT NOT NULL,
 sale_order_id BIGINT,
 msisdn VARCHAR(24),
 imei VARCHAR(24),
 activated_on DATE,
 delivered_on DATE,
 erp_expiry DATE,
 carrier_expiry DATE,
 default_quota_kb BIGINT CHECK(default_quota_kb >= 0),
 INDEX idx_card_customer (customer_id),
 FOREIGN KEY (sku_id) REFERENCES sku(id),
 FOREIGN KEY (carrier_account_id) REFERENCES carrier_account(id),
 FOREIGN KEY (customer_id) REFERENCES party(id),
 FOREIGN KEY (purchase_order_id) REFERENCES erp_order(id),
 FOREIGN KEY (sale_order_id) REFERENCES erp_order(id)
);

CREATE TABLE IF NOT EXISTS usage_cycle (
 iccid VARCHAR(32) NOT NULL,
 starts_on DATE NOT NULL,
 ends_on DATE NOT NULL,
 quota_kb BIGINT NOT NULL CHECK(quota_kb >= 0),
 carrier_kb BIGINT CHECK(carrier_kb >= 0),
 carrier_updated_at TIMESTAMP NULL,
 override_kb BIGINT CHECK(override_kb >= 0),
 override_reason VARCHAR(300),
 override_by BIGINT,
 override_updated_at TIMESTAMP NULL,
 PRIMARY KEY(iccid,starts_on),
 CHECK(ends_on > starts_on),
 FOREIGN KEY (iccid) REFERENCES sim_card(iccid),
 FOREIGN KEY (override_by) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS sim_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 iccid VARCHAR(32) NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(30) NOT NULL,
 detail VARCHAR(300),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (iccid) REFERENCES sim_card(iccid),
 FOREIGN KEY (actor_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS portal_field (
 resource ENUM('orders','cards','inquiries') NOT NULL,
 field_key VARCHAR(50) NOT NULL,
 PRIMARY KEY(resource,field_key)
);

CREATE TABLE IF NOT EXISTS print_template (
 direction ENUM('SALE','PURCHASE') PRIMARY KEY,
 title VARCHAR(100) NOT NULL,
 company VARCHAR(120) NOT NULL DEFAULT '',
 show_price BOOLEAN NOT NULL DEFAULT TRUE,
 show_code BOOLEAN NOT NULL DEFAULT TRUE,
 show_delivery BOOLEAN NOT NULL DEFAULT TRUE,
 footer VARCHAR(250) NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS carrier_snapshot (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 iccid VARCHAR(32) NOT NULL,
 period_month DATE NOT NULL,
 usage_kb DECIMAL(19,3),
 offerings_json JSON NOT NULL,
 unavailable VARCHAR(300) NOT NULL,
 fetched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_carrier_snapshot_card (iccid,id),
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);

CREATE TABLE IF NOT EXISTS group_usage (
 iccid VARCHAR(32) NOT NULL,
 period_month DATE NOT NULL,
 quota_kb DECIMAL(19,3) NOT NULL,
 used_kb DECIMAL(19,3) NOT NULL,
 fetched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(iccid,period_month),
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);

-- 3. Create tables introduced by the prototype pages. Existing tables are kept.
CREATE TABLE IF NOT EXISTS base_reference (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 family VARCHAR(24) NOT NULL,
 code VARCHAR(40) NOT NULL,
 name VARCHAR(120) NOT NULL,
 description VARCHAR(250) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(family,code)
);

CREATE TABLE IF NOT EXISTS master_profile (
 family VARCHAR(20) NOT NULL,
 entity_id BIGINT NOT NULL,
 details TEXT NOT NULL,
 PRIMARY KEY(family,entity_id)
);

CREATE TABLE IF NOT EXISTS order_header (
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

CREATE TABLE IF NOT EXISTS order_line_extra (
 order_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 tax_rate DECIMAL(5,2) NOT NULL DEFAULT 13,
 internal_note VARCHAR(200) NOT NULL DEFAULT '',
 memo VARCHAR(200) NOT NULL DEFAULT '',
 PRIMARY KEY(order_id,sku_id),
 FOREIGN KEY(order_id,sku_id) REFERENCES order_line(order_id,sku_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS warehouse_stock (
 warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 on_hand INT NOT NULL DEFAULT 0, reserved INT NOT NULL DEFAULT 0,
 PRIMARY KEY(warehouse_id,sku_id),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id),
 FOREIGN KEY(sku_id) REFERENCES sku(id),
 CHECK(on_hand>=0 AND reserved>=0 AND on_hand>=reserved)
);

CREATE TABLE IF NOT EXISTS card_location (
 iccid VARCHAR(32) PRIMARY KEY, warehouse_id BIGINT NOT NULL,
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid),
 FOREIGN KEY(warehouse_id) REFERENCES base_reference(id)
);

CREATE TABLE IF NOT EXISTS business_document (
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

CREATE TABLE IF NOT EXISTS sim_plan (
 iccid VARCHAR(32) PRIMARY KEY,
 plan_name VARCHAR(100) NOT NULL DEFAULT '',
 cycle_months INT NOT NULL DEFAULT 1 CHECK(cycle_months BETWEEN 1 AND 24),
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);

CREATE TABLE IF NOT EXISTS cycle_meter (
 iccid VARCHAR(32) NOT NULL, starts_on DATE NOT NULL, month_start DATE NOT NULL,
 raw_baseline DECIMAL(19,3) NOT NULL, confirmed_carry DECIMAL(19,3) NOT NULL,
 PRIMARY KEY(iccid,starts_on,month_start),
 FOREIGN KEY(iccid,starts_on) REFERENCES usage_cycle(iccid,starts_on) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS carrier_services (
 iccid VARCHAR(32) PRIMARY KEY, plan_name VARCHAR(500) NOT NULL DEFAULT '',
 apn_details TEXT, communication_details TEXT,
 FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);

CREATE TABLE IF NOT EXISTS purchase_line_account (
 order_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,carrier_account_id BIGINT NOT NULL,
 PRIMARY KEY(order_id,sku_id),
 FOREIGN KEY(order_id,sku_id) REFERENCES order_line(order_id,sku_id) ON DELETE CASCADE,
 FOREIGN KEY(carrier_account_id) REFERENCES carrier_account(id)
);

CREATE TABLE IF NOT EXISTS carrier_sync_queue (
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

-- 4. Older prototype tables may exist with only part of the columns used by
--    the current application. CREATE TABLE IF NOT EXISTS does not change an
--    existing table, so add every missing column explicitly before indexes,
--    type widening, and backfills run.

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='base_reference'
    AND COLUMN_NAME='description')=0,
 "ALTER TABLE base_reference ADD COLUMN description VARCHAR(250) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='base_reference'
    AND COLUMN_NAME='enabled')=0,
 "ALTER TABLE base_reference ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='delivery_day')=0,
 "ALTER TABLE order_header ADD COLUMN delivery_day DATE NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='manager')=0,
 "ALTER TABLE order_header ADD COLUMN manager VARCHAR(100) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='settlement')=0,
 "ALTER TABLE order_header ADD COLUMN settlement VARCHAR(120) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='notes')=0,
 "ALTER TABLE order_header ADD COLUMN notes VARCHAR(250) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='discount')=0,
 "ALTER TABLE order_header ADD COLUMN discount DECIMAL(16,2) NOT NULL DEFAULT 0",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_header'
    AND COLUMN_NAME='external_code')=0,
 "ALTER TABLE order_header ADD COLUMN external_code VARCHAR(90) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_line_extra'
    AND COLUMN_NAME='internal_note')=0,
 "ALTER TABLE order_line_extra ADD COLUMN internal_note VARCHAR(200) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='order_line_extra'
    AND COLUMN_NAME='memo')=0,
 "ALTER TABLE order_line_extra ADD COLUMN memo VARCHAR(200) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='source_order')=0,
 "ALTER TABLE business_document ADD COLUMN source_order BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='source_doc')=0,
 "ALTER TABLE business_document ADD COLUMN source_doc BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='party_id')=0,
 "ALTER TABLE business_document ADD COLUMN party_id BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='warehouse_id')=0,
 "ALTER TABLE business_document ADD COLUMN warehouse_id BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='target_warehouse')=0,
 "ALTER TABLE business_document ADD COLUMN target_warehouse BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='amount')=0,
 "ALTER TABLE business_document ADD COLUMN amount DECIMAL(16,2) NOT NULL DEFAULT 0",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='state')=0,
 "ALTER TABLE business_document ADD COLUMN state VARCHAR(20) NOT NULL DEFAULT 'DRAFT'",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='revision')=0,
 "ALTER TABLE business_document ADD COLUMN revision INT NOT NULL DEFAULT 0",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='posted_by')=0,
 "ALTER TABLE business_document ADD COLUMN posted_by BIGINT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='created_at')=0,
 "ALTER TABLE business_document ADD COLUMN created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME='posted_at')=0,
 "ALTER TABLE business_document ADD COLUMN posted_at TIMESTAMP NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND COLUMN_NAME='requested_at')=0,
 "ALTER TABLE carrier_sync_queue ADD COLUMN requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND COLUMN_NAME='next_attempt')=0,
 "ALTER TABLE carrier_sync_queue ADD COLUMN next_attempt TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND COLUMN_NAME='lease_token')=0,
 "ALTER TABLE carrier_sync_queue ADD COLUMN lease_token CHAR(36) NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND COLUMN_NAME='attempts')=0,
 "ALTER TABLE carrier_sync_queue ADD COLUMN attempts INT NOT NULL DEFAULT 0",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='sim_plan'
    AND COLUMN_NAME='cycle_months')=0,
 "ALTER TABLE sim_plan ADD COLUMN cycle_months INT NOT NULL DEFAULT 1",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. Add columns that were introduced after the first version of the
--    original business tables. Older installations can be missing these even
--    when the base table already exists.
SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='erp_order'
    AND COLUMN_NAME='inquiry_id')=0,
 'ALTER TABLE erp_order ADD COLUMN inquiry_id BIGINT NULL UNIQUE',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='erp_order'
    AND COLUMN_NAME='round_no')=0,
 'ALTER TABLE erp_order ADD COLUMN round_no INT NOT NULL DEFAULT 0',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='erp_order'
    AND COLUMN_NAME='current_step')=0,
 'ALTER TABLE erp_order ADD COLUMN current_step INT NOT NULL DEFAULT 0',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. Add columns that were introduced after the original print_template table.
SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='print_template' AND COLUMN_NAME='show_code')=0,
 'ALTER TABLE print_template ADD COLUMN show_code BOOLEAN NOT NULL DEFAULT TRUE',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='print_template' AND COLUMN_NAME='show_delivery')=0,
 'ALTER TABLE print_template ADD COLUMN show_delivery BOOLEAN NOT NULL DEFAULT TRUE',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='print_template' AND COLUMN_NAME='footer')=0,
 "ALTER TABLE print_template ADD COLUMN footer VARCHAR(250) NOT NULL DEFAULT ''",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. Extend the portal resource enum without redoing the ALTER on every run.
SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='portal_field'
    AND COLUMN_NAME='resource' AND COLUMN_TYPE LIKE '%inquiries%')=0,
 "ALTER TABLE portal_field MODIFY COLUMN resource ENUM('orders','cards','inquiries') NOT NULL",
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8. Restore indexes that may be absent from a partially applied prototype
--    schema. Check the indexed columns too, because an earlier prototype may
--    have used a smaller table definition.
SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND INDEX_NAME='idx_document_list')=0
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME IN ('kind','business_day','id'))=3,
 'ALTER TABLE business_document ADD INDEX idx_document_list(kind,business_day,id)',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND INDEX_NAME='idx_document_source')=0
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='business_document'
    AND COLUMN_NAME IN ('source_order','kind','state'))=3,
 'ALTER TABLE business_document ADD INDEX idx_document_source(source_order,kind,state)',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
 (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND INDEX_NAME='idx_next_sync')=0
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='carrier_sync_queue'
    AND COLUMN_NAME='next_attempt')=1,
 'ALTER TABLE carrier_sync_queue ADD INDEX idx_next_sync(next_attempt)',
 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 9. Rebuild the public catalog view used by the order and portal APIs.
CREATE OR REPLACE VIEW portal_catalog AS
SELECT s.id,COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.public_name')),''),
       CASE WHEN s.kind='HARDWARE' THEN s.name ELSE CONCAT('物联网卡 ',s.id) END) AS name,
       s.price,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.enabled')),'true') <> 'false' AS enabled
FROM sku s LEFT JOIN master_profile p ON p.family='goods' AND p.entity_id=s.id;

-- 10. Seed reference values without relying on fixed IDs, so this also works
--    when base_reference already contains user-maintained records.
INSERT INTO base_reference(family,code,name)
SELECT d.family,d.code,d.name
FROM (
 SELECT 'warehouse' AS family,'WH-001' AS code,'主仓库' AS name
 UNION ALL SELECT 'unit','UNIT-001','张'
 UNION ALL SELECT 'unit','UNIT-002','台'
 UNION ALL SELECT 'settlement','SET-001','月结 30 天'
 UNION ALL SELECT 'category','CAT-001','物联网卡'
 UNION ALL SELECT 'category','CAT-002','通信设备'
 UNION ALL SELECT 'operator','OP-001','中国移动'
) d
LEFT JOIN base_reference b ON b.family=d.family AND b.code=d.code
WHERE b.id IS NULL;

SET @main_warehouse = (
 SELECT id FROM base_reference
 WHERE family='warehouse'
 ORDER BY enabled DESC,CASE WHEN code='WH-001' THEN 0 ELSE 1 END,id LIMIT 1
);

INSERT INTO portal_field(resource,field_key)
SELECT d.resource,d.field_key
FROM (
 SELECT 'inquiries' AS resource,'id' AS field_key
 UNION ALL SELECT 'inquiries','name'
 UNION ALL SELECT 'inquiries','quantity'
 UNION ALL SELECT 'inquiries','state'
 UNION ALL SELECT 'inquiries','order_id'
 UNION ALL SELECT 'cards','plan_name'
 UNION ALL SELECT 'cards','starts_on'
 UNION ALL SELECT 'cards','ends_on'
 UNION ALL SELECT 'cards','updated_at'
) d
LEFT JOIN portal_field p ON p.resource=d.resource AND p.field_key=d.field_key
WHERE p.field_key IS NULL;

-- Older prototype databases may also have lost comments or shorter text
-- limits on the tables created by 03-prototype.sql. Bring those definitions
-- back in line without touching existing rows.
ALTER TABLE master_profile MODIFY COLUMN details TEXT NOT NULL;
ALTER TABLE order_line_extra MODIFY COLUMN internal_note VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE order_line_extra MODIFY COLUMN memo VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE business_document MODIFY COLUMN memo VARCHAR(250) NOT NULL DEFAULT '';
ALTER TABLE business_document MODIFY COLUMN `lines` MEDIUMTEXT NOT NULL;
ALTER TABLE sim_plan MODIFY COLUMN plan_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE carrier_services MODIFY COLUMN plan_name VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE carrier_services MODIFY COLUMN apn_details TEXT;
ALTER TABLE carrier_services MODIFY COLUMN communication_details TEXT;

-- 11. Backfill rows for records created before the prototype tables existed.
INSERT INTO order_header(order_id,business_day,warehouse_id)
SELECT o.id,DATE(o.created_at),@main_warehouse
FROM erp_order o LEFT JOIN order_header h ON h.order_id=o.id
WHERE h.order_id IS NULL;

INSERT INTO order_line_extra(order_id,sku_id)
SELECT l.order_id,l.sku_id
FROM order_line l LEFT JOIN order_line_extra x
  ON x.order_id=l.order_id AND x.sku_id=l.sku_id
WHERE x.order_id IS NULL;

INSERT INTO warehouse_stock(warehouse_id,sku_id,on_hand,reserved)
SELECT @main_warehouse,i.sku_id,i.on_hand,i.reserved
FROM inventory i LEFT JOIN warehouse_stock w
  ON w.warehouse_id=@main_warehouse AND w.sku_id=i.sku_id
WHERE w.warehouse_id IS NULL;

INSERT INTO card_location(iccid,warehouse_id)
SELECT c.iccid,@main_warehouse
FROM sim_card c LEFT JOIN card_location l ON l.iccid=c.iccid
WHERE l.iccid IS NULL;

INSERT INTO sim_plan(iccid)
SELECT c.iccid FROM sim_card c LEFT JOIN sim_plan p ON p.iccid=c.iccid
WHERE p.iccid IS NULL;

INSERT INTO carrier_sync_queue(iccid,requested_by)
SELECT c.iccid,o.creator_id
FROM sim_card c JOIN erp_order o ON o.id=c.purchase_order_id
LEFT JOIN carrier_sync_queue q ON q.iccid=c.iccid
WHERE q.iccid IS NULL;
