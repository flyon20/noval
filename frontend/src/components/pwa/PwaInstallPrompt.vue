<script setup lang="ts">
import { Close, Monitor, Plus } from '@element-plus/icons-vue';
import { usePwaInstallPrompt } from '@/composables/usePwaInstallPrompt';

const {
  visible,
  canInstall,
  manualGuideVisible,
  platformHint,
  install,
  dismiss,
} = usePwaInstallPrompt();
</script>

<template>
  <aside v-if="visible" class="pwa-install" data-test="pwa-install-prompt">
    <div class="pwa-install__icon" aria-hidden="true">
      <el-icon><Monitor /></el-icon>
    </div>
    <div class="pwa-install__copy">
      <strong>全屏使用更像 App</strong>
      <span v-if="canInstall">安装到桌面后打开，浏览器地址栏和工具栏会让出更多阅读空间。</span>
      <span v-else-if="platformHint === 'ios'" data-test="pwa-install-manual">
        点击浏览器分享按钮，选择“添加到主屏幕”，再从桌面图标打开。
      </span>
      <span v-else data-test="pwa-install-manual">
        打开浏览器菜单，选择“添加到主屏幕”或“安装应用”，再从桌面图标打开。
      </span>
    </div>
    <button
      v-if="canInstall"
      class="pwa-install__action"
      type="button"
      @click="install"
    >
      <el-icon><Plus /></el-icon>
      安装
    </button>
    <button
      v-else-if="manualGuideVisible"
      class="pwa-install__action pwa-install__action--ghost"
      type="button"
      data-test="pwa-install-guide"
      @click="dismiss"
    >
      知道了
    </button>
    <button
      class="pwa-install__close"
      type="button"
      aria-label="关闭安装提示"
      @click="dismiss"
    >
      <el-icon><Close /></el-icon>
    </button>
  </aside>
</template>

<style scoped lang="scss">
.pwa-install {
  display: none;
}

@media (max-width: 768px) {
  .pwa-install {
    position: fixed;
    left: 0.75rem;
    right: 0.75rem;
    bottom: calc(var(--bottom-nav-height) + env(safe-area-inset-bottom, 0px) + 0.75rem);
    z-index: 62;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 0.65rem;
    min-height: 64px;
    padding: 0.65rem;
    border: 1px solid color-mix(in srgb, var(--color-border-strong) 74%, transparent);
    border-radius: 8px;
    color: var(--color-text);
    background: color-mix(in srgb, var(--color-surface-strong) 96%, transparent);
    box-shadow: 0 18px 40px rgba(15, 23, 42, 0.18);
    backdrop-filter: blur(18px) saturate(1.08);
    -webkit-backdrop-filter: blur(18px) saturate(1.08);
  }

  .pwa-install__icon,
  .pwa-install__action,
  .pwa-install__close {
    min-width: 44px;
    min-height: 44px;
  }

  .pwa-install__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    color: #fff;
    background: var(--color-primary);
  }

  .pwa-install__copy {
    display: grid;
    gap: 0.15rem;
    min-width: 0;
  }

  .pwa-install__copy strong {
    font-size: 0.9rem;
  }

  .pwa-install__copy span {
    color: var(--color-text-muted);
    font-size: 0.78rem;
    line-height: 1.35;
  }

  .pwa-install__action,
  .pwa-install__close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid color-mix(in srgb, var(--color-border-strong) 72%, transparent);
    border-radius: 8px;
    background: var(--color-surface);
    color: var(--color-text);
    cursor: pointer;
  }

  .pwa-install__action {
    gap: 0.25rem;
    padding-inline: 0.65rem;
    color: #fff;
    border-color: var(--color-primary);
    background: var(--color-primary);
    font-weight: 600;
  }

  .pwa-install__action--ghost {
    color: var(--color-text);
    border-color: color-mix(in srgb, var(--color-border-strong) 72%, transparent);
    background: var(--color-surface);
  }
}
</style>
