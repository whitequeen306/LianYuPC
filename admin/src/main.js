import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import './management.css'
import App from './App.vue'
createApp(App).use(createPinia()).use(ElementPlus).mount('#app')
