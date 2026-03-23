<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  platform: 'fanqie';
  activeCategory: string;
  categories: Array<{
    label: string;
    value: string;
  }>;
  running?: boolean;
}>();

const emit = defineEmits<{
  select: [category: string];
}>();

const platformLabel = computed(() => (props.platform === 'fanqie' ? '番茄小说' : props.platform));
const activeCategoryLabel = computed(
  () => props.categories.find((item) => item.value === props.activeCategory)?.label ?? '全部榜单',
);
</script>

<template>
  <section class="trend-context">
    <div class="trend-context__copy">
      <p class="trend-context__eyebrow">趋势情报</p>
      <h2 class="trend-context__title">榜单趋势流式分析</h2>
      <p class="trend-context__description">
        结合最新快照和历史分析结果，持续输出当前榜单的主题、节奏和结构变化，并把可视化数据同步整理成中文视图。
      </p>
      <div class="trend-context__chips">
        <span class="trend-context__chip">平台：{{ platformLabel }}</span>
        <span class="trend-context__chip">当前分类：{{ activeCategoryLabel }}</span>
        <span class="trend-context__chip">{{ running ? '状态：分析中' : '状态：待命' }}</span>
      </div>
    </div>

    <div class="trend-context__categories">
      <p class="trend-context__categories-title">榜单分类</p>
      <div class="trend-context__category-list">
        <button
          v-for="item in categories"
          :key="item.value"
          class="trend-context__category"
          :class="{ 'is-active': item.value === activeCategory }"
          type="button"
          :data-test="`trend-category-${item.value}`"
          @click="emit('select', item.value)"
        >
          {{ item.label }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.trend-context {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(260px, 0.7fr);
  gap: 1rem;
  padding: 1.25rem;
  border: 1px solid var(--color-border);
  border-radius: 1.4rem;
  background:
    radial-gradient(circle at top right, rgba(210, 136, 61, 0.16), transparent 28%),
    linear-gradient(135deg, rgba(252, 247, 238, 0.98), rgba(239, 243, 233, 0.92));
  box-shadow: var(--shadow-soft);
}

.trend-context__copy {
  display: grid;
  gap: 0.75rem;
}

.trend-context__eyebrow,
.trend-context__categories-title {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.trend-context__title {
  margin: 0;
  font-size: clamp(1.75rem, 3vw, 2.7rem);
  line-height: 1.08;
}

.trend-context__description {
  margin: 0;
  max-width: 44rem;
  color: var(--color-text-muted);
  line-height: 1.75;
}

.trend-context__chips,
.trend-context__category-list {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.trend-context__chip,
.trend-context__category {
  min-height: 44px;
  padding: 0.65rem 1rem;
  border-radius: 999px;
  border: 1px solid rgba(35, 65, 58, 0.16);
  background: rgba(255, 255, 255, 0.86);
  color: var(--color-text);
  font: inherit;
}

.trend-context__category {
  cursor: pointer;
  font-weight: 600;
  transition:
    transform 160ms ease,
    border-color 160ms ease,
    background 160ms ease;
}

.trend-context__category:hover,
.trend-context__category.is-active {
  border-color: rgba(185, 104, 31, 0.45);
  background: rgba(185, 104, 31, 0.12);
  transform: translateY(-1px);
}

.trend-context__categories {
  display: grid;
  gap: 0.75rem;
  align-content: start;
  padding: 1rem;
  border-radius: 1.2rem;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(35, 65, 58, 0.1);
}

@media (max-width: 980px) {
  .trend-context {
    grid-template-columns: 1fr;
  }
}
</style>
