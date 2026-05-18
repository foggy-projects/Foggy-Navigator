import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import WidgetObservabilityDemo from './dev/WidgetObservabilityDemo.vue'

createApp(WidgetObservabilityDemo).use(ElementPlus).mount('#app')
