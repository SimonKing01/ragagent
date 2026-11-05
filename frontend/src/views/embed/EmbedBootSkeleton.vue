<template>
  <!-- 与 embed.html 里的静态骨架同构；样式来自 public/embed-boot.css（不在此处重写） -->
  <div class="embed-boot" :class="{ 'embed-boot--conversation': variant === 'conversation' }" role="status">
    <div v-if="showHeader" class="embed-boot__header" aria-hidden="true">
      <span class="eb-skel embed-boot__avatar" />
      <span class="eb-skel embed-boot__title" />
      <span class="eb-skel embed-boot__action" />
      <span class="eb-skel embed-boot__action" />
    </div>
    <!-- 有存量会话：画对话气泡；新建会话：画「头像 + 名称 + 问候 + 卡片」迎宾形态 -->
    <div v-if="variant === 'conversation'" class="embed-boot__body embed-boot__body--intro" aria-hidden="true">
      <span class="eb-skel eb-bubble eb-bubble--user" />
      <span class="eb-skel eb-bubble" />
      <span class="eb-skel eb-bubble" />
    </div>
    <div v-else class="embed-boot__body embed-boot__body--intro" aria-hidden="true">
      <span class="eb-skel eb-intro-avatar" />
      <span class="eb-skel eb-intro-name" />
      <span class="eb-skel eb-intro-greeting" />
      <span class="eb-skel eb-intro-label" />
      <span class="eb-skel eb-row" />
      <span class="eb-skel eb-row" />
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
    /** 骨架形态：由调用方按「是否有存量会话」决定，组件自身不做猜测 */
    variant?: 'conversation' | 'welcome'
    /** 页面头部已存在时置 false，避免出现两条头部占位 */
    showHeader?: boolean
  }>(),
  { variant: 'welcome', showHeader: true },
)
</script>
