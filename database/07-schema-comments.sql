-- 在 06 完成后执行。已有字段仅追加备注，保持类型、默认值、约束及自增属性。
USE yunshu;
SET NAMES utf8mb4;

ALTER TABLE `account`
    COMMENT = '用户登录账号',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '账号主键',
    MODIFY COLUMN `username` varchar(80) NOT NULL COMMENT '登录账号',
    MODIFY COLUMN `password_hash` varchar(100) NOT NULL COMMENT '密码哈希',
    MODIFY COLUMN `role` enum('ADMIN','ERP','CUSTOMER') NOT NULL COMMENT '账号角色',
    MODIFY COLUMN `party_id` bigint DEFAULT NULL COMMENT '所关联的客户档案';

ALTER TABLE `approval_assignment`
    COMMENT = '订单提交时冻结的审批任务分配',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '订单主键',
    MODIFY COLUMN `round_no` int NOT NULL COMMENT '审批轮次',
    MODIFY COLUMN `step_no` int NOT NULL COMMENT '审批节点序号',
    MODIFY COLUMN `account_id` bigint NOT NULL COMMENT '本节点可审批的账号';

ALTER TABLE `approval_log`
    COMMENT = '订单审批操作记录',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '审批记录主键',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '订单主键',
    MODIFY COLUMN `round_no` int NOT NULL COMMENT '审批轮次',
    MODIFY COLUMN `step_no` int NOT NULL COMMENT '审批节点序号',
    MODIFY COLUMN `actor_id` bigint NOT NULL COMMENT '操作账号',
    MODIFY COLUMN `action` varchar(30) NOT NULL COMMENT '审批动作',
    MODIFY COLUMN `reason` varchar(300) DEFAULT NULL COMMENT '审批意见或驳回原因',
    MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间';

ALTER TABLE `approval_step`
    COMMENT = '订单审批流程节点配置',
    MODIFY COLUMN `direction` enum('SALE','PURCHASE') NOT NULL COMMENT '订单方向',
    MODIFY COLUMN `step_no` int NOT NULL COMMENT '审批节点序号',
    MODIFY COLUMN `account_id` bigint NOT NULL COMMENT '节点审批人账号';

ALTER TABLE `base_reference`
    COMMENT = '分类、单位、仓库、运营商与结算档案',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '档案主键',
    MODIFY COLUMN `family` varchar(24) NOT NULL COMMENT '档案类别',
    MODIFY COLUMN `code` varchar(40) NOT NULL COMMENT '档案编码',
    MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '档案名称',
    MODIFY COLUMN `description` varchar(250) NOT NULL DEFAULT '' COMMENT '档案描述',
    MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用';

ALTER TABLE `business_document`
    COMMENT = '出入库、退换货、盘点、调拨及财务单据',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '业务单据主键',
    MODIFY COLUMN `kind` varchar(30) NOT NULL COMMENT '业务单据类型',
    MODIFY COLUMN `source_order` bigint DEFAULT NULL COMMENT '来源销售或采购订单',
    MODIFY COLUMN `source_doc` bigint DEFAULT NULL COMMENT '来源发票单据',
    MODIFY COLUMN `party_id` bigint DEFAULT NULL COMMENT '往来单位档案',
    MODIFY COLUMN `warehouse_id` bigint DEFAULT NULL COMMENT '业务仓库或调出仓库',
    MODIFY COLUMN `target_warehouse` bigint DEFAULT NULL COMMENT '调入仓库',
    MODIFY COLUMN `business_day` date NOT NULL COMMENT '单据业务日期',
    MODIFY COLUMN `amount` decimal(16,2) NOT NULL DEFAULT '0.00' COMMENT '单据金额',
    MODIFY COLUMN `memo` varchar(250) NOT NULL DEFAULT '' COMMENT '单据备注',
    MODIFY COLUMN `lines` mediumtext NOT NULL COMMENT '商品和逐卡明细数组，与明细表同步',
    MODIFY COLUMN `state` varchar(20) NOT NULL DEFAULT 'DRAFT' COMMENT '单据状态',
    MODIFY COLUMN `revision` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
    MODIFY COLUMN `created_by` bigint NOT NULL COMMENT '制单账号',
    MODIFY COLUMN `posted_by` bigint DEFAULT NULL COMMENT '最近流转操作账号',
    MODIFY COLUMN `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `posted_at` timestamp NULL DEFAULT NULL COMMENT '最近流转操作时间',
    MODIFY COLUMN `doc_code` varchar(40) DEFAULT NULL COMMENT '已保存的业务单号';

ALTER TABLE `card_location`
    COMMENT = '流量卡所在仓库',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '当前仓库';

ALTER TABLE `carrier_account`
    COMMENT = '移动集团接入账号',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '集团账号主键',
    MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '集团账号名称',
    MODIFY COLUMN `credential_ref` varchar(40) NOT NULL COMMENT '接口凭据配置标识',
    MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用接口调用';

ALTER TABLE `carrier_services`
    COMMENT = '单卡已开通的运营商业务',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `plan_name` varchar(500) NOT NULL DEFAULT '' COMMENT '运营商套餐名称',
    MODIFY COLUMN `apn_details` text COMMENT '接入点业务明细',
    MODIFY COLUMN `communication_details` text COMMENT '通信业务明细';

ALTER TABLE `carrier_snapshot`
    COMMENT = '运营商查询快照',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '快照主键',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `period_month` date NOT NULL COMMENT '运营商统计月份',
    MODIFY COLUMN `usage_kb` decimal(19,3) DEFAULT NULL COMMENT '查询用量（KB）',
    MODIFY COLUMN `offerings_json` json NOT NULL COMMENT '运营商套餐及业务清单',
    MODIFY COLUMN `unavailable` varchar(300) NOT NULL COMMENT '本次未返回的数据项',
    MODIFY COLUMN `fetched_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '查询成功时间';

ALTER TABLE `carrier_sync_queue`
    COMMENT = '流量卡人工对账及门户刷新队列',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '待查询卡的识别号',
    MODIFY COLUMN `requested_by` bigint NOT NULL COMMENT '发起查询的账号',
    MODIFY COLUMN `requested_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求时间',
    MODIFY COLUMN `next_attempt` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可执行时间',
    MODIFY COLUMN `lease_token` char(36) DEFAULT NULL COMMENT '执行租约标识',
    MODIFY COLUMN `attempts` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
    MODIFY COLUMN `last_status` varchar(16) NOT NULL DEFAULT 'QUEUED' COMMENT '最近执行状态';

ALTER TABLE `code_rule`
    COMMENT = '档案与单据编码配置',
    MODIFY COLUMN `rule_key` varchar(32) NOT NULL COMMENT '规则标识',
    MODIFY COLUMN `display_order` tinyint unsigned NOT NULL COMMENT '列表显示顺序',
    MODIFY COLUMN `label` varchar(32) NOT NULL COMMENT '单据类型名称',
    MODIFY COLUMN `prefix` varchar(8) NOT NULL COMMENT '固定编码前缀',
    MODIFY COLUMN `date_policy` enum('OPTIONAL','ALWAYS','NEVER') NOT NULL COMMENT '日期前缀修改策略',
    MODIFY COLUMN `include_date` tinyint(1) NOT NULL COMMENT '是否包含日期',
    MODIFY COLUMN `serial_length` tinyint unsigned NOT NULL DEFAULT '3' COMMENT '流水号位数',
    MODIFY COLUMN `initial_value` int unsigned NOT NULL DEFAULT '1' COMMENT '流水号初始值',
    MODIFY COLUMN `revision` bigint unsigned NOT NULL DEFAULT '0' COMMENT '规则版本';

ALTER TABLE `code_sequence`
    COMMENT = '各类型及日期下已分配的流水号',
    MODIFY COLUMN `rule_key` varchar(32) NOT NULL COMMENT '编码规则标识',
    MODIFY COLUMN `date_part` varchar(6) NOT NULL COMMENT '发号日期段，无日期时为空字符串',
    MODIFY COLUMN `last_serial` int unsigned NOT NULL COMMENT '最近分配的流水号';

ALTER TABLE `cycle_meter`
    COMMENT = '终端卡客户周期与运营商自然月用量衔接',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `starts_on` date NOT NULL COMMENT '客户套餐周期起始日',
    MODIFY COLUMN `month_start` date NOT NULL COMMENT '运营商自然月起始日',
    MODIFY COLUMN `raw_baseline` decimal(19,3) NOT NULL COMMENT '当月起算读数（KB）',
    MODIFY COLUMN `confirmed_carry` decimal(19,3) NOT NULL COMMENT '已确认结转用量（KB）';

ALTER TABLE `erp_order`
    COMMENT = '销售及采购订单',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单主键',
    MODIFY COLUMN `direction` enum('SALE','PURCHASE') NOT NULL COMMENT '订单方向',
    MODIFY COLUMN `party_id` bigint NOT NULL COMMENT '往来单位档案',
    MODIFY COLUMN `inquiry_id` bigint DEFAULT NULL COMMENT '转单前的客户订购申请',
    MODIFY COLUMN `state` enum('DRAFT','PENDING','APPROVED','FULFILLED') NOT NULL DEFAULT 'DRAFT' COMMENT '订单状态',
    MODIFY COLUMN `creator_id` bigint NOT NULL COMMENT '制单账号',
    MODIFY COLUMN `round_no` int NOT NULL DEFAULT '0' COMMENT '当前审批轮次',
    MODIFY COLUMN `current_step` int NOT NULL DEFAULT '0' COMMENT '当前审批节点',
    MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `order_code` varchar(40) DEFAULT NULL COMMENT '已保存的订单编码';

ALTER TABLE `group_usage`
    COMMENT = '集团卡运营商月度用量',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `period_month` date NOT NULL COMMENT '统计月份',
    MODIFY COLUMN `quota_kb` decimal(19,3) NOT NULL COMMENT '月度额度（KB）',
    MODIFY COLUMN `used_kb` decimal(19,3) NOT NULL COMMENT '已用流量（KB）',
    MODIFY COLUMN `fetched_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '运营商数据查询时间';

ALTER TABLE `inquiry`
    COMMENT = '客户门户订购申请',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '申请主键',
    MODIFY COLUMN `customer_id` bigint NOT NULL COMMENT '下单客户',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '订购商品',
    MODIFY COLUMN `quantity` int NOT NULL COMMENT '申请数量',
    MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交申请时间';

ALTER TABLE `inventory`
    COMMENT = '按商品汇总的库存',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `on_hand` int NOT NULL DEFAULT '0' COMMENT '在库数量',
    MODIFY COLUMN `reserved` int NOT NULL DEFAULT '0' COMMENT '已锁定数量';

ALTER TABLE `master_profile`
    COMMENT = '基础档案扩展及历史表单数据',
    MODIFY COLUMN `family` varchar(20) NOT NULL COMMENT '档案类别',
    MODIFY COLUMN `entity_id` bigint NOT NULL COMMENT '对应档案主键',
    MODIFY COLUMN `details` text NOT NULL COMMENT '扩展属性及旧版表单值';

ALTER TABLE `order_header`
    COMMENT = '订单表头业务信息',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '订单主键',
    MODIFY COLUMN `business_day` date NOT NULL COMMENT '订单业务日期',
    MODIFY COLUMN `delivery_day` date DEFAULT NULL COMMENT '交货或到货日期',
    MODIFY COLUMN `manager` varchar(100) NOT NULL DEFAULT '' COMMENT '业务经办人',
    MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '收发货仓库',
    MODIFY COLUMN `settlement` varchar(120) NOT NULL DEFAULT '' COMMENT '结算方式',
    MODIFY COLUMN `external_code` varchar(90) NOT NULL DEFAULT '' COMMENT '客户订单号或供方单号',
    MODIFY COLUMN `notes` varchar(250) NOT NULL DEFAULT '' COMMENT '订单备注',
    MODIFY COLUMN `discount` decimal(16,2) NOT NULL DEFAULT '0.00' COMMENT '整单优惠金额';

ALTER TABLE `order_line`
    COMMENT = '销售与采购订单商品行',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '订单主键',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `quantity` int NOT NULL COMMENT '订购数量',
    MODIFY COLUMN `unit_price` decimal(16,2) NOT NULL COMMENT '含税单价';

ALTER TABLE `order_line_extra`
    COMMENT = '订单商品行税率与备注',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '订单主键',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `tax_rate` decimal(5,2) NOT NULL DEFAULT '13.00' COMMENT '税率（百分比）',
    MODIFY COLUMN `internal_note` varchar(200) NOT NULL DEFAULT '' COMMENT '内部备注',
    MODIFY COLUMN `memo` varchar(200) NOT NULL DEFAULT '' COMMENT '明细备注';

ALTER TABLE `party`
    COMMENT = '客户与供应商档案',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '往来单位主键',
    MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '往来单位名称',
    MODIFY COLUMN `kind` enum('CUSTOMER','SUPPLIER') NOT NULL COMMENT '客户或供应商类别',
    MODIFY COLUMN `code` varchar(40) DEFAULT NULL COMMENT '已保存的档案编码';

ALTER TABLE `portal_field`
    COMMENT = '客户门户字段可见性配置',
    MODIFY COLUMN `resource` enum('orders','cards','inquiries') NOT NULL COMMENT '门户数据类别',
    MODIFY COLUMN `field_key` varchar(50) NOT NULL COMMENT '允许客户查看的字段';

ALTER TABLE `print_template`
    COMMENT = '销售及采购订单打印模板',
    MODIFY COLUMN `direction` enum('SALE','PURCHASE') NOT NULL COMMENT '订单方向',
    MODIFY COLUMN `title` varchar(100) NOT NULL COMMENT '打印标题',
    MODIFY COLUMN `company` varchar(120) NOT NULL DEFAULT '' COMMENT '打印公司名称',
    MODIFY COLUMN `show_price` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否打印单价及金额',
    MODIFY COLUMN `show_code` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否打印商品编码',
    MODIFY COLUMN `show_delivery` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否打印交付日期及仓库',
    MODIFY COLUMN `footer` varchar(250) NOT NULL DEFAULT '' COMMENT '页尾说明';

ALTER TABLE `purchase_line_account`
    COMMENT = '采购单商品的默认移动集团账号',
    MODIFY COLUMN `order_id` bigint NOT NULL COMMENT '采购订单主键',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `carrier_account_id` bigint NOT NULL COMMENT '移动集团账号';

ALTER TABLE `sim_card`
    COMMENT = '流量卡档案与当前销售归属',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '卡片唯一识别号',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `carrier_account_id` bigint NOT NULL COMMENT '所属移动集团账号',
    MODIFY COLUMN `customer_id` bigint DEFAULT NULL COMMENT '当前客户档案',
    MODIFY COLUMN `purchase_order_id` bigint NOT NULL COMMENT '来源采购订单',
    MODIFY COLUMN `sale_order_id` bigint DEFAULT NULL COMMENT '当前销售订单',
    MODIFY COLUMN `msisdn` varchar(24) DEFAULT NULL COMMENT '运营商卡号',
    MODIFY COLUMN `imei` varchar(24) DEFAULT NULL COMMENT '绑定设备识别号',
    MODIFY COLUMN `activated_on` date DEFAULT NULL COMMENT '首次激活日期',
    MODIFY COLUMN `delivered_on` date DEFAULT NULL COMMENT '交付客户日期',
    MODIFY COLUMN `erp_expiry` date DEFAULT NULL COMMENT 'ERP销售有效期',
    MODIFY COLUMN `carrier_expiry` date DEFAULT NULL COMMENT '运营商返回的到期日',
    MODIFY COLUMN `default_quota_kb` bigint DEFAULT NULL COMMENT '终端卡默认独立额度（KB）';

ALTER TABLE `sim_event`
    COMMENT = '流量卡业务操作记录',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '操作记录主键',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `actor_id` bigint NOT NULL COMMENT '操作账号',
    MODIFY COLUMN `action` varchar(30) NOT NULL COMMENT '业务动作',
    MODIFY COLUMN `detail` varchar(300) DEFAULT NULL COMMENT '操作说明',
    MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间';

ALTER TABLE `sim_plan`
    COMMENT = '终端卡客户套餐配置',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `plan_name` varchar(100) NOT NULL DEFAULT '' COMMENT '对客套餐名称',
    MODIFY COLUMN `cycle_months` int NOT NULL DEFAULT '1' COMMENT '客户套餐周期月数';

ALTER TABLE `sku`
    COMMENT = '商品档案',
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品主键',
    MODIFY COLUMN `code` varchar(40) NOT NULL COMMENT '商品编码',
    MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '内部商品名称',
    MODIFY COLUMN `kind` enum('HARDWARE','GROUP','TERMINAL') NOT NULL COMMENT '设备或流量卡类别',
    MODIFY COLUMN `price` decimal(16,2) NOT NULL COMMENT '默认销售价';

ALTER TABLE `usage_cycle`
    COMMENT = '终端卡客户用量周期台账',
    MODIFY COLUMN `iccid` varchar(32) NOT NULL COMMENT '流量卡唯一识别号',
    MODIFY COLUMN `starts_on` date NOT NULL COMMENT '周期开始日',
    MODIFY COLUMN `ends_on` date NOT NULL COMMENT '周期结束日（不含当天）',
    MODIFY COLUMN `quota_kb` bigint NOT NULL COMMENT '周期额度（KB）',
    MODIFY COLUMN `carrier_kb` bigint DEFAULT NULL COMMENT '运营商周期用量（KB）',
    MODIFY COLUMN `carrier_updated_at` timestamp NULL DEFAULT NULL COMMENT '运营商数据更新时间',
    MODIFY COLUMN `override_kb` bigint DEFAULT NULL COMMENT '生效的人工修正用量（KB）',
    MODIFY COLUMN `override_reason` varchar(300) DEFAULT NULL COMMENT '修正原因',
    MODIFY COLUMN `override_by` bigint DEFAULT NULL COMMENT '修正操作账号',
    MODIFY COLUMN `override_updated_at` timestamp NULL DEFAULT NULL COMMENT '人工修正时间';

ALTER TABLE `warehouse_stock`
    COMMENT = '各仓库的商品库存',
    MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库档案编号',
    MODIFY COLUMN `sku_id` bigint NOT NULL COMMENT '商品档案编号',
    MODIFY COLUMN `on_hand` int NOT NULL DEFAULT '0' COMMENT '实际在库数量',
    MODIFY COLUMN `reserved` int NOT NULL DEFAULT '0' COMMENT '已锁定数量';
