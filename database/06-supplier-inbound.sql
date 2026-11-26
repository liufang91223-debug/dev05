-- 供应商入库回填升级脚本（可重复执行）。
-- 合并版本由 migrate.py 在商品分类迁移后执行；兼容已执行本脚本的旧库。
-- 本脚本不会访问任何线上系统，也不会写入供应商凭据。
USE yunshu;
SET @schema_name = DATABASE();

DROP PROCEDURE IF EXISTS _yunshu_add_column;
DROP PROCEDURE IF EXISTS _yunshu_add_index;

DELIMITER $$
CREATE PROCEDURE _yunshu_add_column(IN p_table VARCHAR(64),IN p_column VARCHAR(64),IN p_definition VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME=p_table AND COLUMN_NAME=p_column
  ) THEN
    SET @ddl=CONCAT('ALTER TABLE `',p_table,'` ADD COLUMN `',p_column,'` ',p_definition);
    PREPARE ddl_stmt FROM @ddl;
    EXECUTE ddl_stmt;
    DEALLOCATE PREPARE ddl_stmt;
  END IF;
END$$

CREATE PROCEDURE _yunshu_add_index(IN p_table VARCHAR(64),IN p_index VARCHAR(64),IN p_definition VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME=p_table AND INDEX_NAME=p_index
  ) THEN
    SET @ddl=CONCAT('ALTER TABLE `',p_table,'` ADD INDEX `',p_index,'` ',p_definition);
    PREPARE ddl_stmt FROM @ddl;
    EXECUTE ddl_stmt;
    DEALLOCATE PREPARE ddl_stmt;
  END IF;
END$$
DELIMITER ;

-- 单卡主表：补齐供应商返回的 IMSI 和最近一次回填时间。
CALL _yunshu_add_column('sim_card','imsi','VARCHAR(24) NULL');
CALL _yunshu_add_column('sim_card','last_reconciled_at','TIMESTAMP NULL');

-- 供应商账号：从“中国移动单一接口”扩展为可路由的供应商适配器。
-- 密码仍然只从环境变量读取，config_json 只允许放非敏感配置。
CALL _yunshu_add_column('carrier_account','supplier_type','VARCHAR(20) NOT NULL DEFAULT ''CMCC''');
CALL _yunshu_add_column('carrier_account','api_category','VARCHAR(20) NOT NULL DEFAULT ''CMCC''');
CALL _yunshu_add_column('carrier_account','api_sub_category','VARCHAR(30) NOT NULL DEFAULT ''CMCC_CTBOSS''');
CALL _yunshu_add_column('carrier_account','adapter_key','VARCHAR(40) NOT NULL DEFAULT ''cmcc-ctboss''');
CALL _yunshu_add_column('carrier_account','api_url','VARCHAR(255) NULL');
CALL _yunshu_add_column('carrier_account','config_json','JSON NULL');

-- 队列：补充操作类型、租约截止、错误分类和完成时间，便于重试与排障。
CALL _yunshu_add_column('carrier_sync_queue','operation','VARCHAR(20) NOT NULL DEFAULT ''IDENTITY''');
CALL _yunshu_add_column('carrier_sync_queue','request_id','CHAR(36) NULL');
CALL _yunshu_add_column('carrier_sync_queue','lease_until','TIMESTAMP NULL');
CALL _yunshu_add_column('carrier_sync_queue','last_error','VARCHAR(500) NULL');
CALL _yunshu_add_column('carrier_sync_queue','error_class','VARCHAR(24) NULL');
CALL _yunshu_add_column('carrier_sync_queue','completed_at','TIMESTAMP NULL');
CALL _yunshu_add_column('carrier_sync_queue','updated_at','TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');

CALL _yunshu_add_index('carrier_sync_queue','idx_sync_status','(`last_status`,`next_attempt`)');
CALL _yunshu_add_index('sim_card','idx_card_carrier','(`carrier_account_id`,`last_reconciled_at`)');

-- 历史账号回填适配器标识，避免升级后路由为空。
UPDATE carrier_account
SET adapter_key = CONCAT('cmcc-',LOWER(REPLACE(api_sub_category,'CMCC_','')))
WHERE adapter_key IS NULL OR adapter_key='';

DROP PROCEDURE IF EXISTS _yunshu_add_column;
DROP PROCEDURE IF EXISTS _yunshu_add_index;
