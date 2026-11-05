<template>
  <!-- 与 embed.html 里的静态骨架同构；样式来自 public/embed-boot.css（不在此处重写） -->
  <div class="embed-boot" :class="{ 'embed-boot--conversation': variant === 'conversation' }" role="status">
    <!-- 有存量会话：画对话气泡；新建会话：画「头像 + 名称 + 问候 + 卡片」迎宾形态 -->
    <div v-if="variant === 'conversation'" class="eb-boot-conversation" aria-hidden="true">
      <span class="eb-skel eb-bubble eb-bubble--user" />
      <span class="eb-skel eb-bubble" />
      <span class="eb-skel eb-bubble" />
    </div>
    <div v-else class="eb-boot-welcome" aria-hidden="true">
      <span class="eb-skel eb-intro-avatar" />
      <span class="eb-skel eb-intro-name" />
      <span class="eb-skel eb-intro-greeting" />
      <span class="eb-skel eb-intro-label" />
      <span class="eb-skel eb-row" />
      <span class="eb-skel eb-row" />
    </div>
    <div class="embed-boot__footer" aria-hidden="true">
      <span class="eb-skel eb-input-skel" />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 骨架形态：由调用方按「是否有存量会话」决定，组件自身不做猜测 */
    variant?: 'conversation' | 'welcome'

  }>(),
  { variant: 'welcome' },
)
</script>
