<script setup lang="ts">
const emit = defineEmits<{
  goBack: [];
}>();
</script>

<template>
  <div class="analysis-empty">
    <p class="analysis-empty__eyebrow">尚未准备分析上下文</p>
    <h3>请先在扫榜页选择作品并抓取章节，再进入分析工作台</h3>
    <p>我们会保留 bookId、平台和抓章数量，确保前后端参数保持一致。</p>
    <el-button type="primary" native-type="button" data-test="analysis-empty-go-back" @click="emit('goBack')">
      返回扫榜页
    </el-button>
  </div>
</template>

<style scoped lang="scss">
.analysis-empty {
  border: 1px dashed var(--color-border-strong);
  border-radius: 1.25rem;
  padding: 1.5rem;
  text-align: center;
  background: rgba(255, 255, 255, 0.7);
}

.analysis-empty__eyebrow {
  margin: 0;
  font-size: 0.8rem;
  color: var(--color-text-muted);
  letter-spacing: 0.4em;
}

.analysis-empty h3 {
  margin: 0.6rem 0;
}

.analysis-empty p {
  margin: 0 0 0.75rem;
  color: var(--color-text-muted);
}
</style>
