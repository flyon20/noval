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
const activeBoards = computed(() => activeChannel.value?.boards ?? []);
const activeBoard = computed(() => activeBoards.value.find((item) => item.boardCode === props.activeBoardCode));
const activeBoardName = computed(() => activeBoard.value?.boardName ?? '未选择榜单');
const activeStatus = computed(() => {
  if (props.running) {
    return '分析中';
  }

  if (props.loading) {
    return '加载中';
  }

  return '待命';
});

function handleChannelChange(channelCode: string) {
  const channel = props.channels.find((item) => item.channelCode === channelCode);

  emit('select', {
    channelCode,
    boardCode: channel?.boards[0]?.boardCode ?? '',
  });
}

function handleBoardChange(boardCode: string) {
  emit('select', {
    channelCode: props.activeChannelCode,
    boardCode,
  });
}
</script>

<template>
  <section class="trend-context">
    <div class="trend-context__copy">
      <p class="trend-context__eyebrow">趋势情报</p>
      <div class="trend-context__headline">
        <h2 class="trend-context__title">榜单趋势分析</h2>
        <span class="trend-context__status">状态：{{ activeStatus }}</span>
      </div>
      <div class="trend-context__chips">
        <span class="trend-context__chip">平台：{{ platformLabel }}</span>
        <span class="trend-context__chip">当前榜单：{{ activeBoardName }}</span>
      </div>
    </div>

    <div class="trend-context__field">
      <p class="trend-context__field-label">频道</p>
      <el-select
        :model-value="activeChannelCode"
        class="trend-context__select"
        :loading="loading"
        placeholder="选择频道"
        data-test="trend-channel-select"
        @update:model-value="handleChannelChange"
      >
        <el-option
          v-for="channel in channels"
          :key="channel.channelCode"
          :label="channel.channelName"
          :value="channel.channelCode"
        />
      </el-select>
    </div>

    <div class="trend-context__field">
      <p class="trend-context__field-label">榜单</p>
      <el-select
        :model-value="activeBoardCode"
        class="trend-context__select"
        :loading="loading"
        placeholder="选择榜单"
        data-test="trend-board-select"
        @update:model-value="handleBoardChange"
      >
        <el-option
          v-for="board in activeBoards"
          :key="board.boardCode"
          :label="board.boardName"
          :value="board.boardCode"
        />
      </el-select>
    </div>
  </section>
</template>

<style scoped lang="scss">
.trend-context {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(180px, 220px) minmax(180px, 220px);
  gap: 0.9rem;
  align-items: center;
  padding: 1rem 1.1rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 1.35rem;
  background:
    radial-gradient(circle at top right, rgba(92, 124, 250, 0.18), transparent 26%),
    radial-gradient(circle at bottom left, rgba(255, 147, 186, 0.16), transparent 22%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 92%, transparent);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.1);
  -webkit-backdrop-filter: blur(18px) saturate(1.1);
}

.trend-context__copy,
.trend-context__field {
  display: grid;
  gap: 0.45rem;
  min-width: 0;
}

.trend-context__eyebrow,
.trend-context__field-label {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.trend-context__headline {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

.trend-context__title {
  margin: 0;
}

.trend-context__title {
  font-size: clamp(1.25rem, 2.5vw, 1.8rem);
  line-height: 1.08;
}

.trend-context__status {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0.3rem 0.8rem;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-accent) 18%, transparent);
  color: var(--color-text);
  font-size: 0.88rem;
  font-weight: 600;
}

.trend-context__chips {
  display: flex;
  gap: 0.55rem;
  flex-wrap: wrap;
  min-width: 0;
}

.trend-context__chip {
  min-height: 34px;
  padding: 0.35rem 0.8rem;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  background: color-mix(in srgb, var(--color-glass) 80%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  color: var(--color-text);
  font-size: 0.9rem;
}

.trend-context__select {
  width: 100%;
}

:deep(.trend-context__select .el-select__wrapper) {
  min-height: 42px;
  border-radius: 0.95rem;
  border: 1px solid rgba(35, 65, 58, 0.12);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: none;
}

@media (max-width: 1080px) {
  .trend-context {
    grid-template-columns: minmax(0, 1fr) repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .trend-context {
    grid-template-columns: 1fr;
    align-items: stretch;
    padding: 0.95rem;
  }

  .trend-context__headline {
    align-items: flex-start;
  }

  .trend-context__eyebrow {
    display: none;
  }

  .trend-context__chip {
    max-width: 100%;
    overflow-wrap: anywhere;
  }
}
</style>
