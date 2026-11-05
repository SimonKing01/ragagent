<template>
  <!--
    头部骨架：与真实头部同尺寸、同结构（头像 36 / 昵称 108×14 / 按钮 24），
    这样静态骨架 → Vue 骨架 → 真实头部之间不会跳。
    欢迎形态只画「历史」一个按钮；对话形态再画头像、昵称与「新建」按钮。
  -->
  <!-- 欢迎形态不画头像/名称：判断放在组件内，不依赖父级的 :deep 隐藏规则 -->
  <span v-if="variant === 'conversation'" class="embed-header__badge">
    <span class="eb-skel eb-skel--avatar" />
  </span>
  <div v-if="variant === 'conversation'" class="embed-header__text">
    <span class="eb-skel eb-skel--title" />
  </div>
  <span class="eb-skel eb-skel--action" />
  <span v-if="variant === 'conversation'" class="eb-skel eb-skel--action" />
</template>

<script setup lang="ts">
defineProps<{
  /** 首帧形态：由 localStorage 里有没有该渠道的会话决定 */
  variant: 'welcome' | 'conversation'
}>()
</script>

<style scoped lang="less">
/* 标题容器与真实头部一致：吸收剩余空间，把右侧按钮顶到最右 */
.embed-header__text {
  flex: 1;
  min-width: 0;
}

.eb-skel--avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: block;
}

.eb-skel--title {
  display: block;
  width: 108px;
  height: 14px;
  border-radius: 4px;
}

.eb-skel--action {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  flex: 0 0 auto;
  display: block;
}
</style>
