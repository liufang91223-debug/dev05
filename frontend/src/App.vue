<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Grid, Box, Tickets, House, DataAnalysis, Wallet, UserFilled, Setting, ArrowRight, Connection, Bell, SwitchButton, OfficeBuilding } from '@element-plus/icons-vue'
const LegacyPanel=defineAsyncComponent(()=>import('./LegacyPanel.vue'))
const BusinessPage=defineAsyncComponent(()=>import('./console/BusinessPage.vue'))
const CodeRulesPage=defineAsyncComponent(()=>import('./console/CodeRulesPage.vue'))
const HomePage=defineAsyncComponent(()=>import('./console/HomePage.vue'))
import { api,go,type Row } from './console/api'
import { menu,findPage } from './console/pages'

const user=ref<Row|null>(null), ready=ref(false), busy=ref(false), account=ref(localStorage.getItem('yunshu-login')||''), password=ref('')
const remember=ref(!!localStorage.getItem('yunshu-login')), loc=ref(location.hash), expanded=ref(new Set(['总览','订单管理']))
const icons:Record<string,any>={'总览':Grid,'基础档案':Box,'订单管理':Tickets,'仓库管理':House,'报表管理':DataAnalysis,'财务管理':Wallet,'账户信息':UserFilled,'系统管理':Setting}
const url=computed(()=>new URL((loc.value.slice(1)||'/dashboard'),'https://internal.invalid'))
const parts=computed(()=>url.value.pathname.split('/').filter(Boolean))
const key=computed(()=>parts.value[0]||'dashboard')
const page=computed(()=>findPage(key.value)||menu[0])
const visible=computed(()=>menu.filter(m=>user.value?.role==='ADMIN' || !(m.area==='users'||['carriers','approval','templates','fields'].includes(m.source))))
const groups=computed(()=>[...new Set(visible.value.map(m=>m.group))])
function toggle(name:string){const s=new Set(expanded.value);s.has(name)?s.delete(name):s.add(name);expanded.value=s}
function route(){loc.value=location.hash;expanded.value=new Set([...expanded.value,page.value.group])}
async function start(){await api('/csrf');user.value=await api('/me').catch(()=>null);ready.value=true;route()}
async function login(){
  busy.value=true
  try{await api('/login','POST',new URLSearchParams({username:account.value,password:password.value}))
    if(remember.value)localStorage.setItem('yunshu-login',account.value);else localStorage.removeItem('yunshu-login')
    password.value='';go('dashboard');await start()
  }catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
async function logout(){await api('/logout','POST');user.value=null;go('dashboard');await api('/csrf')}
onMounted(()=>{window.addEventListener('hashchange',route);start().catch(e=>{ElMessage.error(e.message);ready.value=true})})
onUnmounted(()=>window.removeEventListener('hashchange',route))
</script>
<template>
  <div v-if="!ready" class="splash">云枢 ERP</div>
  <div v-else-if="!user" class="sign-screen">
    <div class="sign-box"><div class="sign-story"><div class="logo"><el-icon><Grid/></el-icon><div>云枢 ERP<small>ADMIN CONSOLE</small></div></div>
      <div><span class="eyeline">企业经营一体化工作台</span><h1>云枢 ERP</h1><p>商品、客户、供应商统一建档<br>销售与采购订单全流程追踪<br>卡号用量与销售有效期对账</p></div>
      <span>企业管理后台</span></div>
      <el-form class="sign-form" label-position="top" @submit.prevent="login"><h2>账号登录</h2><p>使用企业账号进入工作台</p>
        <el-form-item label="登录账号"><el-input v-model="account" autocomplete="username" placeholder="请输入账号" size="large"/></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" size="large" show-password/></el-form-item>
        <el-checkbox v-model="remember">记住账号</el-checkbox><el-button native-type="submit" type="primary" size="large" :loading="busy">登录</el-button>
      </el-form></div></div>
  <LegacyPanel v-else-if="user.role==='CUSTOMER'" @signed-out="start"/>
  <div v-else class="console">
    <aside class="navigation"><div class="logo"><el-icon><Grid/></el-icon><div>云枢 ERP<small>ADMIN CONSOLE</small></div></div>
      <div class="tenant"><el-icon><OfficeBuilding/></el-icon><div><small>当前租户</small><strong>企业内部</strong></div></div>
      <nav><div v-for="group in groups" :key="group"><button class="group" @click="toggle(group)"><el-icon><component :is="icons[group]"/></el-icon>{{group}}<span>{{expanded.has(group)?'⌄':'›'}}</span></button>
        <template v-if="expanded.has(group)"><template v-for="(m,i) in visible.filter(x=>x.group===group)" :key="m.key">
          <small v-if="m.section && m.section!==visible.filter(x=>x.group===group)[i-1]?.section" class="subhead">{{m.section}}</small>
          <button :class="['leaf',{active:page.key===m.key}]" @click="go(m.key)">{{m.label}}</button>
        </template></template></div></nav>
      <div class="nav-foot"><b>{{user.username.slice(0,1).toUpperCase()}}</b><div>{{user.username}}<small>{{user.role==='ADMIN'?'系统管理员':'业务人员'}}</small></div></div>
    </aside>
    <div class="work"><header class="top"><div class="crumb"><button @click="go('dashboard')">云枢 ERP</button><el-icon><ArrowRight/></el-icon><span>{{page.group}}</span><el-icon><ArrowRight/></el-icon><button @click="go(page.key)">{{page.label}}</button></div>
      <div class="identity"><el-button text circle title="查看待办" @click="go('dashboard')"><el-icon><Bell/></el-icon></el-button><b>{{user.username.slice(0,1).toUpperCase()}}</b>{{user.username}}<el-button text title="退出登录" @click="logout"><el-icon><SwitchButton/></el-icon></el-button></div></header>
      <main class="workspace">
        <HomePage v-if="page.area==='dashboard'" :user="user"/>
        <CodeRulesPage v-else-if="page.area==='rules'"/>
        <LegacyPanel v-else-if="page.area==='settings' && visible.some(m=>m.key===page.key)" :key="loc" :initial-card="parts[1]" :initial-page="page.source" embedded/>
        <BusinessPage v-else-if="visible.some(m=>m.key===page.key)" :key="loc" :item="page" :route-key="key" :record-id="parts[1]" :query="url.searchParams" :user="user"/>
        <el-empty v-else description="当前账号没有访问权限"/>
      </main>
    </div>
  </div>
</template>
<style>
:root{font-family:"PingFang SC","Microsoft YaHei",system-ui,sans-serif;color:#101828;background:#f3f5f7;--el-color-primary:#0f8e80;--el-color-primary-light-3:#37ae9e;--el-color-primary-light-7:#abe5dd;--el-color-primary-light-9:#edf9f7;--el-color-primary-dark-2:#08786d}*{box-sizing:border-box}body{margin:0}button,a{font:inherit}button{cursor:pointer}
.console{display:grid;grid-template-columns:240px minmax(0,1fr);height:100vh;overflow:hidden}.navigation{display:flex;flex-direction:column;min-height:0;background:#101c2d;color:#cbd5e1}.logo{display:flex;align-items:center;gap:12px;padding:20px 22px;color:white;font-size:19px;font-weight:650;letter-spacing:.03em;border-bottom:1px solid #ffffff12}.logo .el-icon{background:#2dd4bf;color:#0f2634;width:34px;height:34px;border-radius:9px;font-size:23px}.logo small,.nav-foot small,.tenant small{display:block;font-size:10px;line-height:1.5;color:#94a3b8;letter-spacing:.12em}.tenant{display:flex;align-items:center;gap:12px;background:#1d2a3c;border:1px solid #334155;border-radius:9px;margin:14px;padding:11px 14px}.tenant .el-icon{color:#5eead4;font-size:21px}.tenant strong{font-size:13px}
nav{overflow:auto;flex:1;padding:0 10px 16px}.el-button,.group,.leaf{transition:background-color .16s,color .16s,border-color .16s}.work .box,.work .metric{animation:surface-in .16s ease-out}@keyframes surface-in{from{opacity:.72;transform:translateY(2px)}to{opacity:1;transform:none}}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;transition-duration:.01ms!important}}.group,.leaf,.crumb button{background:transparent;border:0;color:inherit}.group,.leaf{display:flex;align-items:center;text-align:left;width:100%;border-radius:7px}.group{gap:10px;font-weight:600;padding:12px;color:#e2e8f0;font-size:14px}.group .el-icon{font-size:16px}.group span{margin-left:auto}.leaf{padding:8px 12px 8px 39px;font-size:13px;color:#b8c5d1}.leaf:hover,.group:hover{background:#ffffff0e}.leaf.active{background:#143e40;color:#5eead4;font-weight:600}.subhead{display:block;padding:10px 34px 4px;color:#8196aa}.nav-foot,.identity,.crumb{display:flex;align-items:center;gap:10px}.nav-foot{padding:15px 20px;border-top:1px solid #ffffff15}.identity b,.nav-foot b{display:grid;place-items:center;height:30px;width:30px;background:#ccfbf1;color:#0f766e;border-radius:50%}.work{display:flex;flex-direction:column;min-width:0;overflow:hidden}.top{height:60px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 26px;background:white;border-bottom:1px solid #e5e7eb;color:#607086;font-size:13px}.crumb .el-icon{font-size:11px}.crumb button{padding:0}.workspace{overflow:auto;padding:24px 28px 48px;flex:1}
.page-head,.bar,.form-actions,.total-line{display:flex;align-items:center;justify-content:space-between;gap:14px}.page-head{margin-bottom:20px}.page-head h1,.workspace h1{font-size:24px;line-height:1.35;margin:0;font-weight:650}.subtitle,.muted{color:#64748b;font-size:13px}.subtitle{margin:5px 0 0}.box,.metric{background:white;border:1px solid #e1e6ea;border-radius:12px}.box{padding:18px 20px;margin-bottom:16px}.box h3{margin:0 0 14px;font-size:16px}.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:14px;margin:0 0 16px}.metric{padding:15px 18px}.metric small{display:block;color:#64748b}.metric strong{display:block;margin-top:9px;font-size:25px;font-variant-numeric:tabular-nums}.metric i{font-style:normal;font-size:12px;font-weight:400;color:#64748b;margin-left:4px}.filters{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.filters label{display:grid;gap:6px;color:#546375;font-size:12px}.filters .el-select{width:145px}.filters .el-input{width:220px}.el-button--primary{--el-button-bg-color:#2dd4bf;--el-button-border-color:#2dd4bf;--el-button-text-color:#103e3b;--el-button-hover-text-color:#073630;--el-button-hover-bg-color:#5eead4;--el-button-hover-border-color:#5eead4;font-weight:600}.bar{font-size:13px;margin-bottom:13px}.gridform{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}.gridform .el-date-editor{max-width:100%}.full{grid-column:1/-1}.form-actions{justify-content:flex-end;position:sticky;bottom:0;background:#fff;border-top:1px solid #e5e7eb;padding:12px 18px;z-index:2}.total-line{justify-content:flex-end;flex-wrap:wrap}.total-line strong,.link{color:#087e75}.link{border:0;background:none;padding:0;font-weight:550}.el-table{font-size:13px!important;--el-table-header-bg-color:#f7f9fb!important;--el-table-header-text-color:#475569!important}.page-end{display:flex;align-items:center;justify-content:space-between;margin-top:14px}.sign-screen,.splash{min-height:100vh;display:grid;place-items:center;background:#f1f5f6}.sign-box{display:grid;grid-template-columns:1.05fr 1fr;width:min(880px,92vw);min-height:460px;border-radius:17px;overflow:hidden;background:white;box-shadow:0 16px 64px #0f172a18}.sign-story{display:flex;flex-direction:column;justify-content:space-between;padding:28px 34px;background:linear-gradient(142deg,#111e30,#153b43);color:white}.sign-story .logo{padding:0;border:0}.eyeline{color:#5eead4}.sign-story h1{font-size:40px;margin:8px 0}.sign-story p{line-height:2;color:#bfceda}.sign-story>span{font-size:12px;color:#94a3b8}.sign-form{align-self:center;padding:40px}.sign-form h2{margin:0}.sign-form p{color:#64748b;margin:7px 0 25px}.sign-form>.el-button{display:flex;width:100%;margin-top:18px}.negative{color:#dc2626}.detail-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.detail-grid small{display:block;color:#728196;margin-bottom:5px}.section-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.section-grid .box{min-width:0}.work a{color:#087e75;text-decoration:none}
@media(max-width:950px){.console{grid-template-columns:185px minmax(0,1fr)}.workspace{padding:16px}.gridform,.detail-grid,.section-grid{grid-template-columns:1fr 1fr}.page-head{align-items:flex-start;flex-wrap:wrap}}@media(max-width:680px){.console{grid-template-columns:140px minmax(0,1fr)}.workspace{padding:8px}.section-grid,.gridform,.sign-box{grid-template-columns:1fr}.sign-story{display:none}.leaf{padding-left:22px}.tenant{margin:6px}}
</style>
