<template>
  <!--
    P0「未知」加载态：还没拿到渠道配置 / 聊天分块未就绪时，不猜测是历史还是新会话，
    只给一个居中的转圈。骨架留给「已经知道形态」之后的阶段。
  -->
  <div class="embed-boot-spinner" role="status">
    <span class="embed-boot-spinner__ring" aria-hidden="true" />
  </div>
</template>

<style scoped lang="less">
.embed-boot-spinner {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 240px;
  background: var(--td-bg-color-container, #fff);

  &__ring {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    border: 2px solid color-mix(in srgb, var(--td-brand-color, #0052d9) 22%, transparent);
    border-top-color: var(--td-brand-color, #0052d9);
    animation: embed-boot-spin 0.8s linear infinite;
  }
}

@keyframes embed-boot-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .embed-boot-spinner__ring {
    animation-duration: 2.4s;
  }
}
</style>
