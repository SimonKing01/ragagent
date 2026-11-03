<template>
  <!-- 与 embed.html 里的静态骨架同构；样式来自 public/embed-boot.css（不在此处重写） -->
  <div class="embed-boot" role="status">
    <span class="embed-boot__sr" aria-live="polite">{{ text }}</span>
    <div v-if="showHeader" class="embed-boot__header" aria-hidden="true">
      <span class="eb-skel embed-boot__avatar" />
      <span class="eb-skel embed-boot__title" />
      <span class="eb-skel embed-boot__action" />
      <span class="eb-skel embed-boot__action" />
    </div>
    <div class="embed-boot__body" aria-hidden="true">
      <span class="eb-skel eb-bubble" />
      <span class="eb-skel eb-bubble eb-bubble--user" />
      <span class="eb-skel eb-row" />
    </div>
    <div class="embed-boot__footer" aria-hidden="true">
      <span class="eb-skel embed-boot__input" />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 读屏文案（等待宿主票 / 正在加载），视觉上隐藏 */
    text: string
    /** 页面头部已存在时置 false，避免出现两条头部占位 */
    showHeader?: boolean
  }>(),
  { showHeader: true },
)
</script>
