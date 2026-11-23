import {createApp, h} from 'vue'
import {ElConfigProvider, ElLoading,
    ElButton, ElCard, ElCheckbox, ElCheckboxGroup, ElCollapse, ElCollapseItem, ElDatePicker, ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination, ElRadio, ElRadioButton, ElRadioGroup, ElSelect, ElStep, ElSteps, ElSwitch, ElTable, ElTableColumn, ElTag
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'

const app=createApp({
  render:()=>h(ElConfigProvider,{locale:zhCn},()=>h(App))
})
const components=[
    ElButton, ElCard, ElCheckbox, ElCheckboxGroup, ElCollapse, ElCollapseItem, ElDatePicker, ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination, ElRadio, ElRadioButton, ElRadioGroup, ElSelect, ElStep, ElSteps, ElSwitch, ElTable, ElTableColumn, ElTag
]
for(const component of components) app.component(component.name!,component)
app.use(ElLoading)
app.mount('#app')
