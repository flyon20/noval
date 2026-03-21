<template>
  <section class="analysis-placeholder">
    <p>第二阶段会在这里接入真实分析页和流式输出。</p>
    <h2>分析页预留中</h2>
    <p>当前已完成登录、扫榜、详情和抓章入口，后续会直接承接这里的路由参数。</p>
  </section>
</template>

<style scoped lang="scss">
.analysis-placeholder {
  display: grid;
  gap: 0.75rem;
  padding: 2rem;
  border: 1px dashed var(--color-border-strong);
  border-radius: 1.25rem;
  background: rgba(255, 255, 255, 0.6);
}

.analysis-placeholder p,
.analysis-placeholder h2 {
  margin: 0;
}

.analysis-placeholder p {
  color: var(--color-text-muted);
}
</style>
