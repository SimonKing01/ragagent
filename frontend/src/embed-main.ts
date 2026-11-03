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

router.isReady().finally(() => {
  app.mount('#embed-app')
})
