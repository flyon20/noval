<script setup lang="ts">
import { computed } from 'vue';
import type { ThemeWordCloudItem } from '@/types/data';

const props = defineProps<{
  items: ThemeWordCloudItem[];
}>();

const WIDTH = 1000;
const HEIGHT = 560;
const CENTER_X = WIDTH / 2;
const CENTER_Y = HEIGHT / 2;
const CLOUD_PADDING = 42;
const palette = [
  'hsl(338 72% 68%)',
  'hsl(22 88% 68%)',
  'hsl(42 92% 67%)',
  'hsl(148 42% 58%)',
  'hsl(198 72% 63%)',
  'hsl(256 68% 70%)',
  'hsl(288 58% 72%)',
  'hsl(12 78% 72%)',
];

type CloudWord = ThemeWordCloudItem & {
  x: number;
  y: number;
  rotate: number;
  fill: string;
  fontSize: number;
  opacity: number;
};

type RingConfig = {
  radiusX: number;
  radiusY: number;
  slots: number;
  jitterX: number;
  jitterY: number;
};

const ringConfigs: RingConfig[] = [
  { radiusX: 52, radiusY: 38, slots: 6, jitterX: 10, jitterY: 8 },
  { radiusX: 162, radiusY: 106, slots: 10, jitterX: 18, jitterY: 14 },
  { radiusX: 278, radiusY: 174, slots: 14, jitterX: 24, jitterY: 18 },
  { radiusX: 396, radiusY: 246, slots: 20, jitterX: 30, jitterY: 22 },
];

function isCjk(char: string) {
  return /[\u3400-\u9fff]/u.test(char);
}

function estimateWordWidth(name: string, fontSize: number) {
  return [...name].reduce((width, char) => width + (isCjk(char) ? fontSize * 0.96 : fontSize * 0.58), 0);
}

function estimateBounds(word: CloudWord) {
  const width = Math.max(estimateWordWidth(word.name, word.fontSize), word.fontSize * 2.1);
  const height = word.fontSize * 1.12;
  return {
    left: word.x - width / 2 - 8,
    right: word.x + width / 2 + 8,
    top: word.y - height / 2 - 8,
    bottom: word.y + height / 2 + 8,
  };
}

function intersects(a: ReturnType<typeof estimateBounds>, b: ReturnType<typeof estimateBounds>) {
  return !(a.right < b.left || a.left > b.right || a.bottom < b.top || a.top > b.bottom);
}

function canPlace(word: CloudWord, placed: CloudWord[]) {
  const bounds = estimateBounds(word);
  if (
    bounds.left < CLOUD_PADDING
    || bounds.right > WIDTH - CLOUD_PADDING
    || bounds.top < CLOUD_PADDING
    || bounds.bottom > HEIGHT - CLOUD_PADDING
  ) {
    return false;
  }
  return placed.every((existing) => !intersects(bounds, estimateBounds(existing)));
}

function resolveRingIndex(ratio: number) {
  if (ratio >= 0.78) return 0;
  if (ratio >= 0.52) return 1;
  if (ratio >= 0.28) return 2;
  return 3;
}

function resolveRotation(index: number, ratio: number) {
  if (ratio >= 0.58) return 0;
  return index % 2 === 0 ? -8 : 8;
}

function buildCandidate(
  item: ThemeWordCloudItem,
  index: number,
  fontSize: number,
  ratio: number,
  ringIndex: number,
  slotIndex: number,
): CloudWord {
  const ring = ringConfigs[Math.min(ringIndex, ringConfigs.length - 1)];
  const angle = ((slotIndex / ring.slots) * Math.PI * 2) + (index * 0.13);
  const jitterFactor = index + slotIndex + 1;
  const jitterX = Math.sin(jitterFactor * 1.7) * ring.jitterX;
  const jitterY = Math.cos(jitterFactor * 1.3) * ring.jitterY;

  return {
    ...item,
    x: CENTER_X + Math.cos(angle) * ring.radiusX + jitterX,
    y: CENTER_Y + Math.sin(angle) * ring.radiusY + jitterY,
    rotate: resolveRotation(index, ratio),
    fill: palette[index % palette.length],
    fontSize,
    opacity: 0.84 + ratio * 0.16,
  };
}

const normalizedItems = computed(() => {
  if (!props.items.length) {
    return [];
  }

  const sorted = [...props.items].sort((left, right) => right.value - left.value);
  const max = Math.max(...sorted.map((item) => item.value), 1);
  const min = Math.min(...sorted.map((item) => item.value), max);
  const placed: CloudWord[] = [];

  for (const [index, item] of sorted.entries()) {
    const ratio = max === min ? 1 : (item.value - min) / (max - min);
    const fontSize = Math.round(18 + ratio * 44);
    const preferredRing = resolveRingIndex(ratio);
    let placedWord: CloudWord | null = null;

    const ringOrder = [preferredRing, preferredRing + 1, preferredRing - 1, preferredRing + 2]
      .filter((ringIndex, ringPos, list) => ringIndex >= 0 && ringIndex < ringConfigs.length && list.indexOf(ringIndex) === ringPos);

    for (const ringIndex of ringOrder) {
      const ring = ringConfigs[ringIndex];
      for (let offset = 0; offset < ring.slots; offset += 1) {
        const slotIndex = (offset + index * 3) % ring.slots;
        const candidate = buildCandidate(item, index, fontSize, ratio, ringIndex, slotIndex);
        if (canPlace(candidate, placed)) {
          placedWord = candidate;
          break;
        }
      }
      if (placedWord) {
        break;
      }
    }

    placed.push(placedWord ?? buildCandidate(item, index, fontSize, ratio, ringConfigs.length - 1, index));
  }

  return placed;
});
</script>

<template>
  <article class="trend-tag-cloud" data-test="trend-tag-cloud">
    <header class="trend-tag-cloud__header">
      <h3>趋势标签云</h3>
    </header>

    <div v-if="normalizedItems.length" class="trend-tag-cloud__body">
      <svg
        class="trend-tag-cloud__svg"
        :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
        role="img"
        aria-label="趋势彩色词云"
      >
        <text
          v-for="(item, index) in normalizedItems"
          :key="item.name"
          :data-test="`trend-tag-cloud-item-${index}`"
          class="trend-tag-cloud__tag"
          :x="item.x"
          :y="item.y"
          :fill="item.fill"
          :font-size="item.fontSize"
          :opacity="item.opacity"
          :transform="`rotate(${item.rotate} ${item.x} ${item.y})`"
        >
          {{ item.name }}
        </text>
      </svg>
    </div>
    <p v-else class="trend-tag-cloud__empty">暂无词云数据</p>
  </article>
</template>

<style scoped lang="scss">
.trend-tag-cloud {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background:
    radial-gradient(circle at 20% 20%, color-mix(in srgb, #ffd9d1 34%, transparent), transparent 32%),
    radial-gradient(circle at 80% 18%, color-mix(in srgb, #d1f4ff 30%, transparent), transparent 28%),
    radial-gradient(circle at 50% 84%, color-mix(in srgb, #dccfff 28%, transparent), transparent 34%),
    linear-gradient(
      165deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: var(--shadow-soft);
  color: var(--color-text);
}

.trend-tag-cloud__header h3,
.trend-tag-cloud__empty {
  margin: 0;
}

.trend-tag-cloud__empty {
  color: var(--color-text-muted);
  line-height: 1.6;
}

.trend-tag-cloud__body {
  min-height: 280px;
}

.trend-tag-cloud__svg {
  width: 100%;
  min-height: 280px;
}

.trend-tag-cloud__tag {
  font-weight: 700;
  letter-spacing: 0.02em;
  dominant-baseline: middle;
  text-anchor: middle;
}
</style>
