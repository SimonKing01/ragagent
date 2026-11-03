<template>
  <!-- 与 embed.html 里的静态骨架同构；样式来自 public/embed-boot.css（不在此处重写） -->
  <div class="embed-boot" :class="{ 'embed-boot--conversation': conversation }" role="status">
    <span class="embed-boot__sr" aria-live="polite">{{ text }}</span>
    <div v-if="showHeader" class="embed-boot__header" aria-hidden="true">
      <span class="eb-skel embed-boot__avatar" />
      <span class="eb-skel embed-boot__title" />
      <span class="eb-skel embed-boot__action" />
      <span class="eb-skel embed-boot__action" />
    </div>
    <!-- 有存量会话时画对话气泡（与历史加载骨架同形），否则画迎宾形态 -->
    <div v-if="conversation" class="embed-boot__body embed-boot__body--intro" aria-hidden="true">
      <span class="eb-skel eb-bubble eb-bubble--user" />
      <span class="eb-skel eb-bubble" />
      <span class="eb-skel eb-bubble" />
    </div>
    <div v-else class="embed-boot__body embed-boot__body--intro" aria-hidden="true">
      <span class="eb-skel eb-intro-greeting" />
      <span class="eb-skel eb-intro-label" />
      <span class="eb-skel eb-row" />
      <span class="eb-skel eb-row" />
    </div>
    <div class="embed-boot__footer" aria-hidden="true">
      <span class="eb-skel embed-boot__input" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { embedChatSessionStorageKey } from '@/api/embed'

withDefaults(
  defineProps<{
    /** 读屏文案（等待宿主票 / 正在加载），视觉上隐藏 */
    text: string
    /** 页面头部已存在时置 false，避免出现两条头部占位 */
    showHeader?: boolean
  }>(),
  { showHeader: true },
)

/** 与 embed.html 内联脚本同口径：本渠道 localStorage 里有会话 ⇒ 画对话骨架。 */
function hasStoredEmbedSession(): boolean {
  try {
    const seg = window.location.pathname.split('/').filter(Boolean)
    const idx = seg.indexOf('embed')
    const channel = idx >= 0 ? seg[idx + 1] : ''
    if (!channel) return false
    return Boolean(window.localStorage.getItem(embedChatSessionStorageKey(channel)))
  } catch {
    return false
  }
}

const conversation = hasStoredEmbedSession()
</script>
