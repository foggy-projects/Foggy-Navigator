import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@foggy/chat/style.css'
import App from './App.vue'
import router from './router'
import { initDraftCleanup } from './composables/useInputMemory'

const app = createApp(App)

app.use(createPinia())
app.use(ElementPlus)
app.use(router)

app.mount('#app')

// Clean up expired drafts once per day on app startup
initDraftCleanup()
