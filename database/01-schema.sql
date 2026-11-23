CREATE DATABASE IF NOT EXISTS yunshu DEFAULT CHARACTER SET utf8mb4;
USE yunshu;

CREATE TABLE party (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  kind ENUM('CUSTOMER','SUPPLIER') NOT NULL
);
CREATE TABLE account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(80) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role ENUM('ADMIN','ERP','CUSTOMER') NOT NULL,
  party_id BIGINT,
  FOREIGN KEY (party_id) REFERENCES party(id)
);
CREATE TABLE sku (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  kind ENUM('HARDWARE','GROUP','TERMINAL') NOT NULL,
  price DECIMAL(16,2) NOT NULL CHECK (price >= 0)
);
CREATE TABLE carrier_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL UNIQUE,
  credential_ref VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE approval_step (
  direction ENUM('SALE','PURCHASE') NOT NULL,
  step_no INT NOT NULL,
  account_id BIGINT NOT NULL,
  PRIMARY KEY(direction, step_no, account_id),
  FOREIGN KEY (account_id) REFERENCES account(id)
);
CREATE TABLE inquiry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (customer_id) REFERENCES party(id),
  FOREIGN KEY (sku_id) REFERENCES sku(id)
);
CREATE TABLE erp_order (
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
CREATE TABLE order_line (
  order_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  unit_price DECIMAL(16,2) NOT NULL CHECK (unit_price >= 0),
  PRIMARY KEY(order_id,sku_id),
  FOREIGN KEY (order_id) REFERENCES erp_order(id),
  FOREIGN KEY (sku_id) REFERENCES sku(id)
);
CREATE TABLE approval_assignment (
  order_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  step_no INT NOT NULL,
  account_id BIGINT NOT NULL,
  PRIMARY KEY(order_id,round_no,step_no,account_id),
  FOREIGN KEY (order_id) REFERENCES erp_order(id),
  FOREIGN KEY (account_id) REFERENCES account(id)
);
CREATE TABLE approval_log (
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
CREATE TABLE inventory (
  sku_id BIGINT PRIMARY KEY,
  on_hand INT NOT NULL DEFAULT 0 CHECK(on_hand >= 0),
  reserved INT NOT NULL DEFAULT 0,
  CHECK(reserved >= 0 AND reserved <= on_hand),
  FOREIGN KEY (sku_id) REFERENCES sku(id)
);
CREATE TABLE sim_card (
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
CREATE TABLE usage_cycle (
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
CREATE TABLE sim_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  iccid VARCHAR(32) NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(30) NOT NULL,
  detail VARCHAR(300),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (iccid) REFERENCES sim_card(iccid),
  FOREIGN KEY (actor_id) REFERENCES account(id)
);
CREATE TABLE portal_field (
  resource ENUM('orders','cards') NOT NULL,
  field_key VARCHAR(50) NOT NULL,
  PRIMARY KEY(resource,field_key)
);
CREATE TABLE print_template (
  direction ENUM('SALE','PURCHASE') PRIMARY KEY,
  title VARCHAR(100) NOT NULL,
  company VARCHAR(120) NOT NULL DEFAULT '',
  show_price BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE carrier_snapshot (
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
CREATE TABLE group_usage (
  iccid VARCHAR(32) NOT NULL,
  period_month DATE NOT NULL,
  quota_kb DECIMAL(19,3) NOT NULL,
  used_kb DECIMAL(19,3) NOT NULL,
  fetched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(iccid,period_month),
  FOREIGN KEY(iccid) REFERENCES sim_card(iccid)
);
