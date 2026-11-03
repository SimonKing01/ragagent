import { createApp, h, type Plugin } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import {
  Button,
  Dialog,
  Drawer,
  Icon,
  ImageViewer,
  Loading,
  Textarea,
  Tooltip,
} from 'tdesign-vue-next'
import 'tdesign-vue-next/es/style/index.css'
import '@/assets/theme/theme.css'
import { installTDesignIconOfflineGuard } from '@/utils/tdesign-icon-offline'
import i18n from './i18n/embed'
import EmbedPage from '@/views/embed/EmbedPage.vue'
import ProtectedResourcePreview from '@/components/ProtectedResourcePreview.vue'

installTDesignIconOfflineGuard()

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/embed/:channelId',
      name: 'embed',
      component: EmbedPage,
    },
  ],
})

// Runtime-only Vue build cannot compile string templates — use a render fn.
const app = createApp({ render: () => [h(RouterView), h(ProtectedResourcePreview)] })

// 只注册 embed 页面实际用到的组件：整包 app.use(TDesign) 会把所有组件与图标库
// 一并拖进首屏 chunk（弱网下首帧要多等 1 MB 以上）。
const EMBED_COMPONENTS: Plugin[] = [Button, Dialog, Drawer, Icon, ImageViewer, Loading, Textarea, Tooltip]
EMBED_COMPONENTS.forEach((component) => {
  app.use(component)
})
app.use(createPinia())
app.use(router)
app.use(i18n)

// 回答气泡里的正文/引用是异步组件：历史会话渲染时才去取分块，会出现
// 「先看到提问、回复晚一拍」。挂载前先把这几个分块预取，通常等历史返回时已就绪。
void import('@/views/chat/components/AgentStreamDisplay.vue')
void import('@/views/chat/components/docInfo.vue')

router.isReady().finally(() => {
  app.mount('#embed-app')
})
