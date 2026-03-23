<script setup lang="ts">
import { computed } from 'vue';
import type { RankBoardCatalog } from '@/types/crawler';

const props = defineProps<{
  platform: 'fanqie';
  channels: RankBoardCatalog[];
  activeChannelCode: string;
  activeBoardCode: string;
  running?: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{
  select: [payload: { channelCode: string; boardCode: string }];
}>();

const platformLabel = computed(() => (props.platform === 'fanqie' ? '番茄小说' : props.platform));
const activeChannel = computed(() => props.channels.find((item) => item.channelCode === props.activeChannelCode));
const activeBoard = computed(() => activeChannel.value?.boards.find((item) => item.boardCode === props.activeBoardCode));
const activeBoardName = computed(() => activeBoard.value?.boardName ?? '未选择榜单');
</script>

<template>
  <section class="trend-context">
    <div class="trend-context__copy">
      <p class="trend-context__eyebrow">趋势情报</p>
      <h2 class="trend-context__title">榜单趋势分析</h2>
      <p class="trend-context__description">
        这里固定围绕你当前选中的榜单做趋势判断，不会自动发起分析。先展示已存的榜单可视化与历史摘要，只有点击按钮时才开始新的流式分析。
      </p>
      <div class="trend-context__chips">
        <span class="trend-context__chip">平台：{{ platformLabel }}</span>
        <span class="trend-context__chip">榜单：{{ activeBoardName }}</span>
        <span class="trend-context__chip">{{ running ? '状态：分析中' : loading ? '状态：加载中' : '状态：待命' }}</span>
      </div>
    </div>

    <div class="trend-context__selectors">
      <div class="trend-context__group">
        <p class="trend-context__group-title">频道</p>
        <div class="trend-context__pill-list">
          <button
            v-for="channel in channels"
            :key="channel.channelCode"
            class="trend-context__pill"
            :class="{ 'is-active': channel.channelCode === activeChannelCode }"
            type="button"
            @click="emit('select', { channelCode: channel.channelCode, boardCode: channel.boards[0]?.boardCode ?? '' })"
          >
            {{ channel.channelName }}
          </button>
        </div>
      </div>

      <div class="trend-context__group">
        <p class="trend-context__group-title">榜单</p>
        <div class="trend-context__pill-list trend-context__pill-list--boards">
          <button
            v-for="board in activeChannel?.boards ?? []"
            :key="board.boardCode"
            class="trend-context__pill trend-context__pill--board"
            :class="{ 'is-active': board.boardCode === activeBoardCode }"
            type="button"
            :data-test="`trend-category-${board.boardCode}`"
            @click="emit('select', { channelCode: activeChannelCode, boardCode: board.boardCode })"
          >
            {{ board.boardName }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.trend-context {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.75fr);
  gap: 1rem;
  padding: 1.25rem;
  border: 1px solid rgba(35, 65, 58, 0.12);
  border-radius: 1.5rem;
  background:
    radial-gradient(circle at top right, rgba(204, 121, 36, 0.18), transparent 28%),
    linear-gradient(135deg, rgba(251, 246, 237, 0.98), rgba(240, 246, 239, 0.94));
  box-shadow: var(--shadow-soft);
}

.trend-context__copy,
.trend-context__selectors,
.trend-context__group {
  display: grid;
  gap: 0.75rem;
}

.trend-context__eyebrow,
.trend-context__group-title {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.82rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.trend-context__title,
.trend-context__description {
  margin: 0;
}

.trend-context__title {
  font-size: clamp(1.55rem, 3vw, 2.4rem);
  line-height: 1.08;
}

.trend-context__description {
  color: var(--color-text-muted);
  line-height: 1.8;
}

.trend-context__chips,
.trend-context__pill-list {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.trend-context__chip,
.trend-context__pill {
  min-height: 44px;
  padding: 0.68rem 1rem;
  border-radius: 999px;
  border: 1px solid rgba(35, 65, 58, 0.12);
  background: rgba(255, 255, 255, 0.9);
  color: var(--color-text);
  font: inherit;
}

.trend-context__pill {
  cursor: pointer;
  font-weight: 600;
  transition:
    transform 160ms ease,
    border-color 160ms ease,
    background 160ms ease;
}

.trend-context__pill:hover,
.trend-context__pill.is-active {
  border-color: rgba(190, 108, 28, 0.4);
  background: rgba(190, 108, 28, 0.14);
  transform: translateY(-1px);
}

.trend-context__pill-list--boards {
  max-height: 11rem;
  overflow: auto;
  padding-right: 0.25rem;
}

@media (max-width: 980px) {
  .trend-context {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .trend-context {
    padding: 1rem;
  }

  .trend-context__pill-list {
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 0.2rem;
  }

  .trend-context__pill {
    white-space: nowrap;
  }
}
</style>
