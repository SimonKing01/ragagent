<template>
  <!--
    P0「未知」加载态：还没拿到渠道配置 / 聊天分块未就绪时，不猜测是历史还是新会话，
    只给一个居中的转圈（样式与 embed.html 静态段共用 public/embed-boot.css）。
  -->
  <div class="eb-boot-spinner" role="status">
    <span class="eb-boot-spinner__ring" aria-hidden="true" />
  </div>
</template>
