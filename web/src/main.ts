import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { setUnauthorizedHandler } from './api/client'
import 'element-plus/dist/index.css'
import './styles/main.css'

const pinia = createPinia()
const auth = useAuthStore(pinia)
setUnauthorizedHandler(() => {
  auth.logout()
  void router.replace({ name: 'login' })
})

createApp(App).use(pinia).use(router).mount('#app')
