#!/usr/bin/env python3
"""Integration regression: creates and drops only random yunshu_mtest_* databases."""
import os
import re
import subprocess
import sys
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MYSQL = ['mysql', '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4']
if os.getenv('MYSQL_TEST_USER'):
    MYSQL += ['--user=' + os.environ['MYSQL_TEST_USER']]


class Migrations(unittest.TestCase):
    def setUp(self):
        self.name = 'yunshu_mtest_' + uuid.uuid4().hex[:12]
        self.sql(f'CREATE DATABASE `{self.name}` CHARACTER SET utf8mb4')

    def tearDown(self):
        self.sql(f'DROP DATABASE `{self.name}`')

    def sql(self, sql, ok=True):
        result = subprocess.run(MYSQL, input=sql, text=True, capture_output=True)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        return result

    def query(self, sql):
        return self.sql(f'USE `{self.name}`; {sql}').stdout.strip()

    def script(self, name):
        self.sql(re.sub(r'\byunshu\b', self.name, (ROOT / name).read_text()))

    def migrate(self, *options, ok=True):
        cmd = [sys.executable, str(ROOT / 'migrate.py'), '--database', self.name, *options]
        if os.getenv('MYSQL_TEST_USER'):
            cmd += ['--user', os.environ['MYSQL_TEST_USER']]
        result = subprocess.run(cmd, text=True, capture_output=True)
        if ok:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def legacy(self):
        for name in ('01-schema.sql', '02-demo.sql', '03-prototype.sql'):
            self.script(name)

    def assert_repeat_preserves_values(self):
        self.query("UPDATE sku SET public_name='新门户名称',memo='新字段',code='MANUAL-KEEP' WHERE id=1; UPDATE carrier_account SET api_url='http://127.0.0.1:9999',adapter_key='custom-adapter' WHERE id=1; UPDATE code_rule SET initial_value=42 WHERE rule_key='goods';")
        before = self.query("SELECT code,public_name,memo FROM sku WHERE id=1; SELECT api_url,adapter_key FROM carrier_account WHERE id=1; SELECT initial_value FROM code_rule WHERE rule_key='goods'; SELECT VIEW_DEFINITION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='portal_catalog';")
        self.migrate()
        after = self.query("SELECT code,public_name,memo FROM sku WHERE id=1; SELECT api_url,adapter_key FROM carrier_account WHERE id=1; SELECT initial_value FROM code_rule WHERE rule_key='goods'; SELECT VIEW_DEFINITION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='portal_catalog';")
        self.assertEqual(before, after)
        self.assertEqual(self.query("SELECT COUNT(*) FROM schema_migration WHERE status='done'"), '7')

    def test_fresh_and_repeat(self):
        self.migrate('--demo')
        self.assertEqual(self.query('SELECT COUNT(*) FROM code_rule'), '16')
        self.assert_repeat_preserves_values()
        result = self.sql(re.sub(r'\byunshu\b', self.name, (ROOT / '05-upgrade.sql').read_text()), ok=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('legacy_upgrade_use_migrate_py', result.stderr)

    def test_local_supplier_database_upgrade(self):
        self.legacy()
        self.script('06-supplier-inbound.sql')
        self.query("""
          INSERT INTO account(id,username,password_hash,role) VALUES(10,'preserved-user','unchanged-hash','ADMIN');
          INSERT INTO base_reference(id,family,code,name) VALUES(100,'category','CAT1','一级'),(101,'category','CAT2','二级'),(102,'category','CAT3','三级');
          INSERT INTO master_profile(family,entity_id,details) VALUES
            ('category',101,'{"parent":"一级"}'),('category',102,'{"parent":"二级"}'),
            ('goods',1,'{"category":"三级","public_name":"旧门户名称","brand":"旧品牌"}');
          INSERT INTO erp_order(id,direction,party_id,creator_id) VALUES(10,'PURCHASE',2,10);
          INSERT INTO sim_card(iccid,sku_id,carrier_account_id,purchase_order_id,imsi,msisdn) VALUES('test-iccid',1,1,10,'kept-imsi','kept-msisdn');
          INSERT INTO carrier_sync_queue(iccid,requested_by,last_error,attempts) VALUES('test-iccid',10,'kept-error',3);
          INSERT INTO business_document(id,kind,business_day,created_by,`lines`) VALUES(10,'purchase_inbound',CURRENT_DATE,10,'[{"sku":1,"quantity":1,"cards":[{"iccid":"test-iccid","carrier":1}]}]');
        """)
        self.migrate()
        self.assertEqual(self.query("SELECT password_hash FROM account WHERE id=10"), 'unchanged-hash')
        self.assertEqual(self.query("SELECT public_name,brand,category_id FROM sku WHERE id=1"), '旧门户名称\t旧品牌\t102')
        self.assertEqual(self.query('SELECT id,reference_count FROM product_category WHERE id>=100 ORDER BY id'), '100\t1\n101\t1\n102\t1')
        self.assertEqual(self.query("SELECT imsi,msisdn FROM sim_card WHERE iccid='test-iccid'; SELECT last_error,attempts FROM carrier_sync_queue WHERE iccid='test-iccid'"), 'kept-imsi\tkept-msisdn\nkept-error\t3')
        self.assertEqual(self.query('SELECT sku_id,quantity FROM business_document_line WHERE document_id=10; SELECT iccid FROM business_document_card WHERE document_id=10'), '1\t1\ntest-iccid')
        self.query("UPDATE business_document SET `lines`='[{\"sku\":1,\"quantity\":2}]' WHERE id=10")
        self.assertEqual(self.query('SELECT quantity FROM business_document_line WHERE document_id=10; SELECT COUNT(*) FROM business_document_card WHERE document_id=10'), '2\n0')
        self.assert_repeat_preserves_values()

    def test_github_database_adoption(self):
        self.legacy()
        for name in ('05-code-rules.sql', '06-page-fields.sql', '07-schema-comments.sql', '08-product-categories.sql'):
            self.script(name)
        self.query("UPDATE sku SET public_name='已经在新版编辑',code='KEEP-CODE' WHERE id=1")
        self.migrate()
        self.assertEqual(self.query('SELECT public_name,code FROM sku WHERE id=1'), '已经在新版编辑\tKEEP-CODE')
        self.assert_repeat_preserves_values()

    def test_partial_migration_refused(self):
        self.legacy()
        self.query('CREATE TABLE code_rule(broken INT)')
        result = self.migrate(ok=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('部分迁移', result.stderr)
        self.assertEqual(self.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schema_migration'"), '0')

    def test_failed_category_upgrade_cannot_repeat_blindly(self):
        self.legacy()
        self.query("INSERT INTO master_profile VALUES('goods',1,'{\"category\":\"不存在的分类\"}')")
        failed = self.migrate(ok=False)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn('不存在的分类', failed.stderr)
        self.assertEqual(self.query("SELECT status FROM schema_migration WHERE version='product-categories'"), 'started')
        self.assertEqual(self.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_category'"), '0')
        self.assertIn('上次未完成', self.migrate(ok=False).stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
