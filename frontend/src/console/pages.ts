export type Area='register'|'order'|'doc'|'report'|'cards'|'settings'|'dashboard'|'users'
export interface MenuItem { key:string; label:string; area:Area; source:string; prefix?:string; group:string; section?:string }
const rows:MenuItem[]=[]
function group(name:string,area:Area,entries:[string,string,string,string?,string?][]){
  for(const [key,label,source,prefix,section] of entries)rows.push({key,label,area,source,prefix,group:name,section})
}
group('总览','dashboard',[['dashboard','工作台','dashboard']])
group('基础档案','register',[
  ['goods_list','商品管理','goods','SP'],['goods_category_list','商品分类','category','CAT'],
  ['customer_list','客户管理','customer','CUS'],['supplier_list','供应商管理','supplier','SUP'],
  ['unit_list','计量单位','unit','UNIT'],['warehouse_list','仓库档案','warehouse','WH'],
  ['operator_list','运营商管理','operator','OP'],['aging_archive','结算方式','settlement','SET']])
group('订单管理','order',[['sales_order_list','销售订单','SALE','SO'],['purchase_order_list','采购订单','PURCHASE','PO']])
group('订单管理','cards',[['sales_order_id_list','销售ID列表','sold']])
group('订单管理','doc',[['sales_return_list','销售退货','sales_return','ST'],['sales_exchange_list','换货出库','sales_exchange','EX'],['purchase_return_list','采购退货','purchase_return','PR']])
group('仓库管理','doc',[['purchase_inbound_list','采购入库','purchase_inbound','PI','入库管理'],['other_inbound_list','其它入库','other_inbound','OI','入库管理'],
 ['sales_outbound_list','销售出库','sales_outbound','OS','出库管理'],['other_outbound_list','其它出库','other_outbound','OT','出库管理']])
group('仓库管理','report',[['inventory_list','库存列表','stock','', '库存管理']])
group('仓库管理','doc',[['stock_taking_list','仓库盘点','stock_taking','ST'],['transfer_list','调拨管理','transfer','TR']])
group('报表管理','report',[['report_salesOrder_detail','销售订单明细报表','sales'],['report_purchaseOrder_detail','采购订单明细报表','purchase'],
 ['report_inventory_stock','库存明细报表','stock'],['report_receivable_summary','应收账款汇总报表','receivable'],
 ['report_inbound_detail','入库明细报表','inbound'],['report_outbound_detail','出库明细报表','outbound']])
group('财务管理','doc',[['sales_invoice_list','销售发票','sales_invoice','INV'],['purchase_invoice_list','采购发票','purchase_invoice','PIN'],
 ['receipt_list','收款','receipt','RC'],['payment_list','付款','PM']])
group('账户信息','users',[['employee_list','人员管理','accounts'],['role_list','角色管理','roles']])
group('系统管理','settings',[['carriers','运营商接口配置','carriers'],['reconciliation','流量卡查询与对账','cards'],
 ['inquiries','客户订购申请','inquiries'],['approval','审批流程','approval'],['templates','打印模板','templates'],['fields','客户可见字段','fields']])
export const menu=rows
export function board(item:MenuItem,mode:'form'|'detail'){
  return item.key.replace(/_list$/,'')+'_'+mode
}
export function findPage(name:string){
  if(name==='inventory_id_list' || name==='purchase_order_id_list')return menu.find(m=>m.key==='sales_order_id_list')
  if(name.endsWith('_export'))return menu.find(m=>m.key===name.replace(/_export$/,'_list')||m.key===name.replace(/_export$/,'_detail'))
  return menu.find(m=>m.key===name || [board(m,'form'),board(m,'detail')].includes(name) ||
    (m.area==='order' && [m.key.replace('_list','_import'),m.key.replace('_list','_export')].includes(name)))
}
