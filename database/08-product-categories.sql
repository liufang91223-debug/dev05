-- 在 07 之后执行一次，升级期间暂停应用写入。
USE yunshu;
SET NAMES utf8mb4;

-- 旧页面保存名称。同名或失效名称须核对，支持用 @code:分类编码 明确目标。
DROP TEMPORARY TABLE IF EXISTS cat08_edges,cat08_parents,cat08_goods,cat08_errors;
CREATE TEMPORARY TABLE cat08_edges AS
SELECT c.id, c.parent AS old_value, MIN(p.id) AS parent_id, COUNT(p.id) AS matches_found
FROM base_reference c LEFT JOIN base_reference p ON p.family='category' AND
    CASE WHEN LEFT(c.parent,6)='@code:' THEN p.code=SUBSTRING(c.parent,7)
         ELSE NULLIF(TRIM(c.parent),'')=p.name END
WHERE c.family='category' GROUP BY c.id,c.parent;

CREATE TEMPORARY TABLE cat08_goods AS
SELECT s.id,s.category AS old_value,MIN(c.id) AS category_id,COUNT(c.id) AS matches_found
FROM sku s LEFT JOIN base_reference c ON c.family='category' AND
    CASE WHEN LEFT(s.category,6)='@code:' THEN c.code=SUBSTRING(s.category,7)
         ELSE NULLIF(TRIM(s.category),'')=c.name END
GROUP BY s.id,s.category;

ALTER TABLE cat08_edges ADD PRIMARY KEY(id), ADD INDEX(parent_id);
ALTER TABLE cat08_goods ADD PRIMARY KEY(id), ADD INDEX(category_id);
CREATE TEMPORARY TABLE cat08_parents AS SELECT id,parent_id FROM cat08_edges;
ALTER TABLE cat08_parents ADD PRIMARY KEY(id);
CREATE TEMPORARY TABLE cat08_errors (source_type VARCHAR(30),record_id BIGINT,old_value VARCHAR(250));
INSERT INTO cat08_errors SELECT '分类上级',id,old_value FROM cat08_edges WHERE NULLIF(TRIM(old_value),'') IS NOT NULL AND matches_found<>1;
INSERT INTO cat08_errors SELECT '商品分类',id,old_value FROM cat08_goods WHERE NULLIF(TRIM(old_value),'') IS NOT NULL AND matches_found<>1;
INSERT INTO cat08_errors
SELECT '分类循环或超过三级',c.id,c.old_value FROM cat08_edges c
JOIN cat08_parents p ON p.id=c.parent_id JOIN base_reference g ON g.id=p.parent_id
WHERE NULLIF(TRIM(g.parent),'') IS NOT NULL;
INSERT INTO cat08_errors
SELECT '单据明细商品不存在',l.document_id,CAST(l.sku_id AS CHAR)
FROM business_document_line l LEFT JOIN sku s ON s.id=l.sku_id WHERE s.id IS NULL;
DROP PROCEDURE IF EXISTS check_category_migration;
DELIMITER $$
CREATE PROCEDURE check_category_migration()
BEGIN
    IF EXISTS(SELECT 1 FROM cat08_errors) THEN
        SELECT source_type AS 问题类型,record_id AS 原记录编号,old_value AS 原值 FROM cat08_errors ORDER BY source_type,record_id;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='请先修正以上分类关联并重新执行 08，业务表尚未变更';
    END IF;
END$$
DELIMITER ;
CALL check_category_migration();
DROP PROCEDURE check_category_migration;

CREATE TABLE product_category (
    id BIGINT PRIMARY KEY COMMENT '分类档案主键，与基础档案共用编号',
    parent_id BIGINT NULL COMMENT '直接上级分类，一级分类为空',
    reference_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '直接子分类数与直接关联商品数之和，最小为零',
    FOREIGN KEY(id) REFERENCES base_reference(id),
    FOREIGN KEY(parent_id) REFERENCES product_category(id)
) COMMENT='商品分类层级与引用次数';
-- 档案写事务先锁定本行，避免同时移动分类、选用分类及删除分类发生竞态。
CREATE TABLE product_category_lock (
    id TINYINT PRIMARY KEY COMMENT '商品及分类关系事务的固定锁行', CHECK (id=1)
) COMMENT='商品分类关系写入互斥锁';
INSERT INTO product_category_lock(id) VALUES(1);
ALTER TABLE sku ADD COLUMN category_id BIGINT NULL COMMENT '关联的商品分类编号，旧版未分类商品可为空',
    ADD CONSTRAINT fk_sku_category FOREIGN KEY(category_id) REFERENCES product_category(id);

ALTER TABLE business_document_line ADD CONSTRAINT fk_doc_line_sku FOREIGN KEY(sku_id) REFERENCES sku(id);

START TRANSACTION;
INSERT INTO product_category(id) SELECT id FROM cat08_edges;
UPDATE product_category c JOIN cat08_edges e ON e.id=c.id SET c.parent_id=e.parent_id;
UPDATE sku s JOIN cat08_goods m ON m.id=s.id LEFT JOIN base_reference c ON c.id=m.category_id
SET s.category_id=m.category_id,s.category=c.name WHERE m.category_id IS NOT NULL;
UPDATE base_reference b JOIN cat08_edges e ON e.id=b.id JOIN base_reference p ON p.id=e.parent_id SET b.parent=p.name;
UPDATE product_category c SET reference_count=
    (SELECT COUNT(*) FROM cat08_edges e WHERE e.parent_id=c.id)+(SELECT COUNT(*) FROM cat08_goods s WHERE s.category_id=c.id);
COMMIT;

CREATE VIEW product_category_tree AS
SELECT b.id,b.family,b.code,b.name,b.description,b.enabled,b.memo,c.parent_id,p.name AS parent,c.reference_count,
       CASE WHEN g.id IS NOT NULL THEN 3 WHEN p.id IS NOT NULL THEN 2 ELSE 1 END AS category_level,
       CONCAT_WS(' / ',g.name,p.name,b.name) AS category_path
FROM product_category c JOIN base_reference b ON b.id=c.id
LEFT JOIN product_category up ON up.id=c.parent_id LEFT JOIN base_reference p ON p.id=up.id
LEFT JOIN base_reference g ON g.id=up.parent_id;

DROP TEMPORARY TABLE cat08_edges,cat08_parents,cat08_goods,cat08_errors;
