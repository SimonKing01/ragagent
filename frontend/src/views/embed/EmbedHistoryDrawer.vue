<template>
  <Transition name="embed-history-fade">
    <div v-if="open" class="embed-history">
      <div class="embed-history__mask" @click="emit('close')" />
      <aside
        class="embed-history__panel"
        role="dialog"
        :aria-label="t('embedPublish.historyTitle')"
      >
        <header class="embed-history__head">
          <h2 class="embed-history__heading">{{ t('embedPublish.historyTitle') }}</h2>
          <t-button
            variant="text"
            shape="square"
            size="medium"
            :title="t('common.close')"
            :aria-label="t('common.close')"
            @click="emit('close')"
          >
            <template #icon><t-icon name="close" size="18px"/></template>
          </t-button>
        </header>

        <div class="embed-history__body">
          <div v-if="loading" class="embed-history__skeleton" aria-hidden="true">
            <span v-for="n in 5" :key="n" class="embed-history__sk-row" />
          </div>
          <div v-else-if="groups.length === 0" class="embed-history__empty">
            <t-icon name="history" size="32px" />
            <p>{{ t('embedPublish.historyEmpty') }}</p>
          </div>
          <template v-else>
            <section v-for="group in groups" :key="group.key" class="embed-history__group">
              <h3 class="embed-history__group-label">{{ group.label }}</h3>
              <button
                v-for="item in group.items"
                :key="item.id"
                type="button"
                class="embed-history__item"
                :class="{ 'is-current': item.id === currentSessionId }"
                @click="emit('select', item)"
              >
                <span class="embed-history__title">
                  {{ item.title?.trim() || t('embedPublish.defaultChatTitle') }}
                </span>
                <span v-if="item.id === currentSessionId" class="embed-history__badge">
                  {{ t('embedPublish.historyCurrent') }}
                </span>
                <span class="embed-history__time">{{ formatTime(item.updatedAt) }}</span>
                <span
                  class="embed-history__delete"
                  role="button"
                  tabindex="0"
                  :aria-label="t('embedPublish.historyDelete')"
                  :title="t('embedPublish.historyDelete')"
                  @click.stop="requestDelete(item)"
                  @keydown.enter.stop="requestDelete(item)"
                  @keydown.space.stop.prevent="requestDelete(item)"
                >
                  <span>{{ t('embedPublish.historyDelete') }}</span>
                </span>
              </button>
            </section>
          </template>
        </div>
      </aside>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { computed, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  classifyDateBucket,
  groupSessionsByDate,
  type DateBucketKey,
} from '@/components/sessionGrouping'
import type { EmbedVisitorSessionSummary } from '@/api/embed'

const props = defineProps<{
  open: boolean
  loading: boolean
  sessions: EmbedVisitorSessionSummary[]
  currentSessionId: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'select', item: EmbedVisitorSessionSummary): void
  // 二次确认由父组件用 t-dialog 呈现（可控制 z-index，避免被抽屉遮挡）
  (e: 'delete-request', item: EmbedVisitorSessionSummary): void
}>()

const requestDelete = (item: EmbedVisitorSessionSummary) => {
  emit('delete-request', item)
}

const { t } = useI18n()

// 分区复用首页同一套纯函数（今天/昨天/近7天/近30天/近一年/更早）；
// 列表已按最后活动倒序，这里只分区、不再排序。
const groups = computed(() => {
  if (props.sessions.length === 0) return []
  const bucketLabels: Record<DateBucketKey, string> = {
    pinned: t('embedPublish.historyTitle'),
    today: t('embedPublish.historyTime.today'),
    yesterday: t('embedPublish.historyTime.yesterday'),
    last7Days: t('embedPublish.historyTime.last7Days'),
    last30Days: t('embedPublish.historyTime.last30Days'),
    lastYear: t('embedPublish.historyTime.lastYear'),
    earlier: t('embedPublish.historyTime.earlier'),
  }
  return groupSessionsByDate(
    props.sessions,
    bucketLabels,
    (item) => classifyDateBucket(item.updatedAt),
  )
})

const formatTime = (iso: string) => {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    const hh = String(date.getHours()).padStart(2, '0')
    const mm = String(date.getMinutes()).padStart(2, '0')
    return `${hh}:${mm}`
  }
  return `${date.getMonth() + 1}/${date.getDate()}`
}

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') emit('close')
}

watch(
  () => props.open,
  (open) => {
    if (typeof document === 'undefined') return
    if (open) document.addEventListener('keydown', onKeydown)
    else document.removeEventListener('keydown', onKeydown)
  },
)

onUnmounted(() => {
  if (typeof document !== 'undefined') document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped lang="less">
.embed-history {
  position: fixed;
  inset: 0;
  z-index: 3000;
}

.embed-history__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.28);
}

.embed-history__panel {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: min(320px, 82%);
  display: flex;
  flex-direction: column;
  background: var(--td-bg-color-container, #fff);
  box-shadow: 4px 0 20px rgba(0, 0, 0, 0.12);
  animation: embed-history-slide-in 0.22s ease;
}

@keyframes embed-history-slide-in {
  from { transform: translateX(-100%); }
  to { transform: translateX(0); }
}

.embed-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 8px 12px 16px;
  border-bottom: 1px solid var(--td-component-stroke);
  flex-shrink: 0;
}

.embed-history__heading {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.embed-history__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.embed-history__group-label {
  margin: 10px 8px 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--td-text-color-placeholder);
}

.embed-history__item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  background: transparent;
  border-radius: 6px;
  padding: 9px 10px;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--td-text-color-primary);

  &:hover {
    background: var(--td-bg-color-container-hover, rgba(0, 0, 0, 0.04));
  }

  &.is-current {
    background: color-mix(in srgb, var(--td-brand-color, #0052d9) 10%, transparent);
  }
}

.embed-history__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.embed-history__badge {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1;
  padding: 3px 5px;
  border-radius: 4px;
  color: var(--td-brand-color, #0052d9);
  background: color-mix(in srgb, var(--td-brand-color, #0052d9) 14%, transparent);
}

.embed-history__time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.embed-history__item:hover .embed-history__time,
.embed-history__item:focus-within .embed-history__time {
  display: none;
}

.embed-history__delete {
  display: none;
  flex-shrink: 0;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--td-error-color, #d54941);
  cursor: pointer;
}

.embed-history__item:hover .embed-history__delete,
.embed-history__item:focus-within .embed-history__delete {
  display: inline-flex;
}

.embed-history__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 48px 16px;
  color: var(--td-text-color-placeholder);
  font-size: 13px;

  p {
    margin: 0;
  }
}

.embed-history__skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 8px;
}

.embed-history__sk-row {
  height: 34px;
  border-radius: 6px;
  background: linear-gradient(90deg, rgba(0, 0, 0, 0.05) 25%, rgba(0, 0, 0, 0.08) 37%, rgba(0, 0, 0, 0.05) 63%);
  background-size: 400% 100%;
  animation: embed-history-shimmer 1.2s ease-in-out infinite;
}

@keyframes embed-history-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.embed-history-fade-enter-active,
.embed-history-fade-leave-active {
  transition: opacity 0.18s ease;
}

.embed-history-fade-enter-from,
.embed-history-fade-leave-to {
  opacity: 0;
}

</style>
