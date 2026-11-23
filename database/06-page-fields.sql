-- 在 05 完成后暂停应用写入，使用 MySQL 8 执行一次。
USE yunshu;
SET NAMES utf8mb4;

-- 先校验旧表单 JSON 和合同日期，转换失败时业务表仍是升级前的结构。
CREATE TEMPORARY TABLE page06_profiles (
    family VARCHAR(20) NOT NULL, entity_id BIGINT NOT NULL, details JSON NOT NULL,
    contract_expiry DATE NULL, PRIMARY KEY(family,entity_id)
);
INSERT INTO page06_profiles(family,entity_id,details,contract_expiry)
SELECT family,entity_id,details,
       CASE WHEN family='customer' THEN NULLIF(JSON_VALUE(details,'$.expiry'),'') END
FROM master_profile;

ALTER TABLE sku
    ADD COLUMN `public_name` VARCHAR(250) NULL COMMENT '客户门户展示商品名',
    ADD COLUMN `spec` VARCHAR(250) NULL COMMENT '规格型号',
    ADD COLUMN `category` VARCHAR(250) NULL COMMENT '商品分类名称',
    ADD COLUMN `unit` VARCHAR(250) NULL COMMENT '基本计量单位名称',
    ADD COLUMN `brand` VARCHAR(250) NULL COMMENT '品牌',
    ADD COLUMN `safety_stock` VARCHAR(250) NULL COMMENT '安全库存设置值',
    ADD COLUMN `memo` VARCHAR(250) NULL COMMENT '业务备注',
    ADD COLUMN `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用';

UPDATE sku t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='goods'
SET t.`public_name`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.public_name')),
    t.`spec`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.spec')),
    t.`category`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.category')),
    t.`unit`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.unit')),
    t.`brand`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.brand')),
    t.`safety_stock`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.safety_stock')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo')),
    t.`enabled`=COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.enabled')),'true') <> 'false';

ALTER TABLE party
    ADD COLUMN `level` VARCHAR(250) NULL COMMENT '客户或供应商等级',
    ADD COLUMN `manager` VARCHAR(250) NULL COMMENT '业务负责人',
    ADD COLUMN `contact` VARCHAR(250) NULL COMMENT '联系人',
    ADD COLUMN `phone` VARCHAR(250) NULL COMMENT '联系电话',
    ADD COLUMN `settlement` VARCHAR(250) NULL COMMENT '收付款条件',
    ADD COLUMN `expiry` DATE NULL COMMENT '客户合同到期日',
    ADD COLUMN `address` VARCHAR(250) NULL COMMENT '联系地址',
    ADD COLUMN `memo` VARCHAR(250) NULL COMMENT '业务备注',
    ADD COLUMN `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用';

UPDATE party t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='customer' AND t.kind='CUSTOMER'
SET t.`level`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.level')),
    t.`manager`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.manager')),
    t.`contact`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.contact')),
    t.`phone`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.phone')),
    t.`settlement`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.settlement')),
    t.`expiry`=p.contract_expiry,
    t.`address`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.address')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo')),
    t.`enabled`=COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.enabled')),'true') <> 'false';

UPDATE party t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='supplier' AND t.kind='SUPPLIER'
SET t.`level`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.level')),
    t.`manager`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.manager')),
    t.`contact`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.contact')),
    t.`phone`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.phone')),
    t.`settlement`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.settlement')),
    t.`address`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.address')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo')),
    t.`enabled`=COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.enabled')),'true') <> 'false';

ALTER TABLE base_reference
    ADD COLUMN `wh_type` VARCHAR(250) NULL COMMENT '仓库类型',
    ADD COLUMN `address` VARCHAR(250) NULL COMMENT '联系地址',
    ADD COLUMN `manager` VARCHAR(250) NULL COMMENT '业务负责人',
    ADD COLUMN `phone` VARCHAR(250) NULL COMMENT '联系电话',
    ADD COLUMN `locations` VARCHAR(250) NULL COMMENT '库位',
    ADD COLUMN `memo` VARCHAR(250) NULL COMMENT '业务备注',
    ADD COLUMN `parent` VARCHAR(250) NULL COMMENT '上级分类名称',
    ADD COLUMN `symbol` VARCHAR(250) NULL COMMENT '计量单位符号',
    ADD COLUMN `contact` VARCHAR(250) NULL COMMENT '联系人',
    ADD COLUMN `basis` VARCHAR(250) NULL COMMENT '账期起算点',
    ADD COLUMN `days` VARCHAR(250) NULL COMMENT '账期天数设置值';

UPDATE base_reference t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='warehouse' AND t.family=p.family
SET t.`wh_type`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.wh_type')),
    t.`address`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.address')),
    t.`manager`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.manager')),
    t.`phone`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.phone')),
    t.`locations`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.locations')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo'));

UPDATE base_reference t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='category' AND t.family=p.family
SET t.`parent`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.parent')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo'));

UPDATE base_reference t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='unit' AND t.family=p.family
SET t.`symbol`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.symbol')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo'));

UPDATE base_reference t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='operator' AND t.family=p.family
SET t.`contact`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.contact')),
    t.`phone`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.phone')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo'));

UPDATE base_reference t JOIN page06_profiles p ON p.entity_id=t.id AND p.family='settlement' AND t.family=p.family
SET t.`basis`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.basis')),
    t.`days`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.days')),
    t.`memo`=JSON_UNQUOTE(JSON_EXTRACT(p.details,'$.memo'));

DROP TEMPORARY TABLE page06_profiles;

CREATE OR REPLACE VIEW portal_catalog AS
SELECT id,COALESCE(NULLIF(public_name,''),CASE WHEN kind='HARDWARE' THEN name ELSE CONCAT('物联网卡 ',id) END) AS name,
       price,enabled FROM sku;

CREATE TABLE business_document_line (
    document_id BIGINT NOT NULL COMMENT '所属业务单据',
    line_no INT UNSIGNED NOT NULL COMMENT '商品行序号，从一开始',
    sku_id BIGINT NOT NULL COMMENT '商品档案编号',
    quantity INT NOT NULL COMMENT '业务数量，盘点单为实盘数量',
    erp_expiry DATE NULL COMMENT '出库时录入的销售有效期',
    replacement_iccid TEXT COMMENT '换货时选定的替换卡 ICCID',
    PRIMARY KEY(document_id,line_no),
    KEY idx_document_line_sku(sku_id),
    FOREIGN KEY(document_id) REFERENCES business_document(id) ON DELETE CASCADE
) COMMENT='业务单据商品明细，含库存、退换货及盘点调拨';

CREATE TABLE business_document_card (
    document_id BIGINT NOT NULL COMMENT '所属业务单据',
    line_no INT UNSIGNED NOT NULL COMMENT '所属商品行序号',
    card_no INT UNSIGNED NOT NULL COMMENT '行内卡片序号，从一开始',
    iccid TEXT COMMENT '单据录入或选择的卡片 ICCID',
    carrier_account_id BIGINT NULL COMMENT '本次单据选择的移动集团账号，未指定时可为零',
    activated_on DATE NULL COMMENT '录单时已知的首次激活日',
    quota_kb BIGINT NULL COMMENT '本次单据配置的单卡额度，单位 KB',
    plan_name TEXT COMMENT '本次单据配置的客户套餐名',
    cycle_months INT NULL COMMENT '客户套餐周期月数',
    PRIMARY KEY(document_id,line_no,card_no),
    KEY idx_document_card_iccid(iccid(32)),
    FOREIGN KEY(document_id,line_no) REFERENCES business_document_line(document_id,line_no) ON DELETE CASCADE
) COMMENT='业务单据逐卡明细，保留制单时的配置';

-- 保留原接口的明细数组，并在同一事务内同步可查询的行、卡字段。

START TRANSACTION;
INSERT INTO business_document_line(document_id,line_no,sku_id,quantity,erp_expiry,replacement_iccid)
SELECT d.id,j.line_no,j.sku_id,j.quantity,j.erp_expiry,j.replacement_iccid
FROM business_document d CROSS JOIN JSON_TABLE(d.`lines`,'$[*]' COLUMNS (
        line_no FOR ORDINALITY, sku_id BIGINT PATH '$.sku', quantity INT PATH '$.quantity',
        erp_expiry DATE PATH '$.expiry', replacement_iccid TEXT PATH '$.replacement'
    )) AS j;
INSERT INTO business_document_card(document_id,line_no,card_no,iccid,carrier_account_id,activated_on,quota_kb,plan_name,cycle_months)
SELECT d.id,j.line_no,j.card_no,j.iccid,j.carrier_account_id,j.activated_on,j.quota_kb,j.plan_name,j.cycle_months
FROM business_document d CROSS JOIN JSON_TABLE(d.`lines`,'$[*]' COLUMNS (
        line_no FOR ORDINALITY,
        NESTED PATH '$.cards[*]' COLUMNS (
            card_no FOR ORDINALITY, iccid TEXT PATH '$.iccid', carrier_account_id BIGINT PATH '$.carrier',
            activated_on DATE PATH '$.activatedOn', quota_kb BIGINT PATH '$.quotaKb',
            plan_name TEXT PATH '$.planName', cycle_months INT PATH '$.cycleMonths'
        )
    )) AS j WHERE j.card_no IS NOT NULL;
COMMIT;

DELIMITER $$
CREATE PROCEDURE sync_document_rows(IN doc_id BIGINT,IN payload MEDIUMTEXT)
MODIFIES SQL DATA
BEGIN
    DELETE FROM business_document_line WHERE document_id=doc_id;
    INSERT INTO business_document_line(document_id,line_no,sku_id,quantity,erp_expiry,replacement_iccid)
    SELECT doc_id,j.line_no,j.sku_id,j.quantity,j.erp_expiry,j.replacement_iccid
    FROM JSON_TABLE(payload,'$[*]' COLUMNS (
            line_no FOR ORDINALITY, sku_id BIGINT PATH '$.sku', quantity INT PATH '$.quantity',
            erp_expiry DATE PATH '$.expiry', replacement_iccid TEXT PATH '$.replacement'
        )) AS j;
    INSERT INTO business_document_card(document_id,line_no,card_no,iccid,carrier_account_id,activated_on,quota_kb,plan_name,cycle_months)
    SELECT doc_id,j.line_no,j.card_no,j.iccid,j.carrier_account_id,j.activated_on,j.quota_kb,j.plan_name,j.cycle_months
    FROM JSON_TABLE(payload,'$[*]' COLUMNS (
            line_no FOR ORDINALITY,
            NESTED PATH '$.cards[*]' COLUMNS (
                card_no FOR ORDINALITY, iccid TEXT PATH '$.iccid', carrier_account_id BIGINT PATH '$.carrier',
                activated_on DATE PATH '$.activatedOn', quota_kb BIGINT PATH '$.quotaKb',
                plan_name TEXT PATH '$.planName', cycle_months INT PATH '$.cycleMonths'
            )
        )) AS j WHERE j.card_no IS NOT NULL;
END$$
CREATE TRIGGER sync_new_document AFTER INSERT ON business_document FOR EACH ROW
BEGIN
    CALL sync_document_rows(NEW.id,NEW.`lines`);
END$$
CREATE TRIGGER sync_changed_document AFTER UPDATE ON business_document FOR EACH ROW
BEGIN
    IF BINARY OLD.`lines` <> BINARY NEW.`lines` THEN
        CALL sync_document_rows(NEW.id,NEW.`lines`);
    END IF;
END$$
DELIMITER ;
