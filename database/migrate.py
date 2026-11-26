#!/usr/bin/env python3
"""Run the merged MySQL migrations once, in dependency order (Python 3 + mysql)."""
import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


class MigrationError(Exception):
    pass


class Database:
    def __init__(self, args):
        self.name = args.database
        self.command = [args.mysql, '--batch', '--raw', '--skip-column-names',
                        '--default-character-set=utf8mb4']
        for key in ('user', 'host', 'port'):
            if getattr(args, key):
                self.command.append(f'--{key}={getattr(args, key)}')

    def sql(self, sql):
        result = subprocess.run(self.command, input=sql, text=True, capture_output=True)
        if result.returncode:
            raise MigrationError(result.stdout + result.stderr)
        return result.stdout.strip()

    def query(self, sql):
        return self.sql(f'USE `{self.name}`;\n{sql}')


def columns_from_create(source):
    """Require the columns defined in each historical CREATE TABLE statement."""
    found = set()
    for match in re.finditer(r'CREATE TABLE(?: IF NOT EXISTS)? (\w+)\s*\((.*?)\n\)', source, re.S):
        for column in re.finditer(r'(?:^|,)\s*`?(\w+)`?\s+(?:BIGINT|INT|TINYINT|VARCHAR|CHAR|TEXT|MEDIUMTEXT|DECIMAL|BOOLEAN|DATE|TIMESTAMP|JSON|ENUM)\b', match[2], re.I):
            found.add((match[1], column[1]))
    return found


def fingerprint(db):
    columns = {tuple(row.split('\t')) for row in db.query(
        'SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()').splitlines()}
    objects = set(db.query("SELECT CONCAT('routine:',ROUTINE_NAME) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() UNION ALL SELECT CONCAT('trigger:',TRIGGER_NAME) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() UNION ALL SELECT CONCAT('view:',TABLE_NAME) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE()").splitlines())
    constraints = set(db.query("SELECT CONCAT('fk:',CONSTRAINT_NAME) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()").splitlines())
    return columns, objects | constraints


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default='yunshu')
    parser.add_argument('--user')
    parser.add_argument('--host')
    parser.add_argument('--port', type=int)
    parser.add_argument('--mysql', default='mysql')
    parser.add_argument('--check', action='store_true', help='read-only migration plan')
    parser.add_argument('--demo', action='store_true', help='seed 02-demo.sql on a new database only')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_]{1,64}', args.database):
        parser.error('database must contain only letters, digits and underscores')
    db = Database(args)
    exists = db.sql(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{db.name}'") == '1'
    columns, objects = fingerprint(db) if exists else (set(), set())
    tables = {table for table, _ in columns}
    fresh = not (tables - {'schema_migration'})
    ledger = {}
    if 'schema_migration' in tables:
        for row in db.query('SELECT version,checksum,status FROM schema_migration').splitlines():
            version, checksum, status = row.split('\t')
            ledger[version] = (checksum, status)
    if args.demo and not fresh:
        raise MigrationError('--demo 仅用于空库；已有业务库不会导入演示数据。')

    sources = {p.name: p.read_text() for p in ROOT.glob('*.sql')}
    core = columns_from_create(sources['01-schema.sql'])
    prototype = columns_from_create(sources['03-prototype.sql'])
    code = columns_from_create(sources['05-code-rules.sql']) | {('party', 'code'), ('erp_order', 'order_code'), ('business_document', 'doc_code')}
    code_objects = {'routine:allocate_erp_code'} | {f'trigger:code_new_{s}' for s in ('goods', 'party', 'reference', 'order', 'document')}
    page = columns_from_create(sources['06-page-fields.sql'])
    page |= {(table, field) for table, fields in {
        'sku': 'public_name spec category unit brand safety_stock memo enabled',
        'party': 'level manager contact phone settlement expiry address memo enabled',
        'base_reference': 'wh_type address manager phone locations memo parent symbol contact basis days',
    }.items() for field in fields.split()}
    page_objects = {'routine:sync_document_rows', 'trigger:sync_new_document', 'trigger:sync_changed_document', 'view:portal_catalog'}
    category = columns_from_create(sources['08-product-categories.sql']) | {('sku', 'category_id')}
    category_objects = {'view:product_category_tree', 'fk:fk_sku_category', 'fk:fk_doc_line_sku'}

    steps = [
        ('core', '01-schema.sql', core, set(), bool(core & columns)),
        ('prototype', '05-upgrade.sql', prototype, {'view:portal_catalog'}, False),
        ('code-rules', '05-code-rules.sql', code, code_objects, bool(code & columns)),
        ('page-fields', '06-page-fields.sql', page, page_objects, bool(page & columns)),
        ('schema-comments', '07-schema-comments.sql', set(), set(), False),
        ('product-categories', '08-product-categories.sql', category, category_objects, bool(category & columns)),
        ('supplier-inbound', '06-supplier-inbound.sql', set(), set(), False),
    ]
    # Only the legacy normalizer may run on pre-code-rule installations. It would
    # rebuild the old JSON portal view if run against an already upgraded schema.
    modern = bool(code & columns) or bool({'code_rule', 'code_sequence'} & tables)
    steps[2] = (*steps[2][:-1], modern)
    steps[3] = (*steps[3][:-1], bool(page & columns) or bool({'business_document_line', 'business_document_card'} & tables))
    steps[5] = (*steps[5][:-1], bool(category & columns) or bool({'product_category', 'product_category_lock'} & tables))
    plan = []
    for version, filename, required, required_objects, marked in steps:
        checksum = hashlib.sha256(sources[filename].encode()).hexdigest()
        if version in ledger:
            old_checksum, status = ledger[version]
            if status != 'done':
                raise MigrationError(f'{version} 上次未完成；MySQL DDL 不会自动回滚。请核查错误并从备份恢复后重试，禁止直接清除记录。')
            if checksum != old_checksum:
                raise MigrationError(f'{filename} 校验和已改变，请新增迁移而不要修改已执行脚本。')
            if not required <= columns or not required_objects <= objects:
                raise MigrationError(f'{version} 已记录完成，但结构缺失，请先修复数据库。')
            action = 'skip'
        elif version == 'prototype' and modern:
            if not prototype <= columns or 'view:portal_catalog' not in objects:
                raise MigrationError('旧库基础结构不完整，不能接管已执行编码规则的数据库。')
            action = 'adopt'
        elif marked:
            if not required <= columns or not required_objects <= objects:
                missing = sorted(map(str, (required - columns) | (required_objects - objects)))
                raise MigrationError(f'{version} 发现部分迁移痕迹，停止以避免覆盖数据。缺少：{missing}')
            action = 'adopt'
        else:
            action = 'apply'
        plan.append((version, filename, checksum, action))
        print(f'{action:5} {version:20} {filename}')
    if args.check:
        return

    sql = [f'CREATE DATABASE IF NOT EXISTS `{db.name}` DEFAULT CHARACTER SET utf8mb4; USE `{db.name}`;',
           # Held by the SAME mysql session throughout the entire migration.
           f"SET @migration_lock=GET_LOCK('{db.name}:schema_migration',10);",
           'CREATE TEMPORARY TABLE _erp_lock_check(owned TINYINT NOT NULL CHECK(owned=1)); INSERT INTO _erp_lock_check VALUES(@migration_lock);',
           'DELIMITER $$\nDROP PROCEDURE IF EXISTS _erp_migration_guard$$\nCREATE PROCEDURE _erp_migration_guard(IN ok BOOLEAN) BEGIN IF NOT COALESCE(ok,FALSE) THEN SIGNAL SQLSTATE \'45000\' SET MESSAGE_TEXT=\'Migration locked or plan changed; run --check again\'; END IF; END$$\nDELIMITER ;',
           'CALL _erp_migration_guard(@migration_lock=1);',
           "CREATE TABLE IF NOT EXISTS schema_migration(version VARCHAR(40) PRIMARY KEY, checksum CHAR(64) NOT NULL, status ENUM('started','done') NOT NULL, source VARCHAR(80) NOT NULL, completed_at TIMESTAMP NULL);"]
    for version, filename, checksum, action in plan:
        if action == 'skip':
            sql.append(f"CALL _erp_migration_guard(EXISTS(SELECT 1 FROM schema_migration WHERE version='{version}' AND checksum='{checksum}' AND status='done'));")
            continue
        sql.extend([f"CALL _erp_migration_guard(NOT EXISTS(SELECT 1 FROM schema_migration WHERE version='{version}'));",
                    f"INSERT INTO schema_migration(version,checksum,status,source) VALUES('{version}','{checksum}','started','{filename}');"])
        if action == 'apply':
            sql.append(re.sub(r'\byunshu\b', db.name, sources[filename]))
            if version == 'core' and args.demo:
                sql.append(re.sub(r'\byunshu\b', db.name, sources['02-demo.sql']))
        sql.append(f"UPDATE schema_migration SET status='done',completed_at=CURRENT_TIMESTAMP WHERE version='{version}';")
    sql.extend(['DROP PROCEDURE _erp_migration_guard;', f"DO RELEASE_LOCK('{db.name}:schema_migration');"])
    output = db.sql('\n'.join(sql))
    if output:
        print(output)
    print('迁移完成。')


if __name__ == '__main__':
    try:
        main()
    except (MigrationError, OSError) as error:
        print(f'迁移停止：{error}', file=sys.stderr)
        sys.exit(1)
