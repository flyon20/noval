<script setup lang="ts">
import { reactive } from 'vue';
import { useRouter } from 'vue-router';
import { User, Lock } from '@element-plus/icons-vue';
import { authApi } from '@/api/auth';
import { systemApi } from '@/api/system';
import { HOME_ROUTE } from '@/constants/auth';
import { DEFAULT_PLATFORM } from '@/constants/crawler';
import { getErrorPayload } from '@/lib/http-error';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const authStore = useAuthStore();

const form = reactive({
  username: '',
  password: '',
});

const state = reactive({
  submitting: false,
  errorMessage: '',
  traceId: '',
});

function triggerLoginBootstrap() {
  try {
    void systemApi.loginBootstrap({ platform: DEFAULT_PLATFORM }).catch((error) => {
      console.warn('login bootstrap failed', error);
    });
  } catch (error) {
    console.warn('login bootstrap failed', error);
  }
}

async function handleSubmit() {
  if (!form.username.trim() || !form.password.trim()) {
    state.errorMessage = '请输入用户名和密码';
    state.traceId = '';
    return;
  }

  state.submitting = true;
  state.errorMessage = '';
  state.traceId = '';

  try {
    const response = await authApi.login({
      username: form.username.trim(),
      password: form.password,
    });

    authStore.applyTokenResponse(response.data.data);
    triggerLoginBootstrap();
    await router.push(HOME_ROUTE);
  } catch (error) {
    const payload = getErrorPayload(error);
    state.errorMessage = payload.message ?? '登录失败，请稍后重试';
    state.traceId = payload.traceId ?? '';
  } finally {
    state.submitting = false;
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-page__panel login-page__hero">
      <div class="login-page__hero-inner">
        <p class="login-page__eyebrow">NOVAL STUDIO</p>
        <h1 class="login-page__headline">小说分析<br />工作台</h1>
        <p class="login-page__description">
          洞察番茄榜单趋势，<br />赋能内容决策。
        </p>
        <div class="login-page__features">
          <div class="login-page__feature-item">
            <span class="login-page__feature-dot"></span>
            实时榜单爬取分析
          </div>
          <div class="login-page__feature-item">
            <span class="login-page__feature-dot"></span>
            AI 驱动内容解构
          </div>
          <div class="login-page__feature-item">
            <span class="login-page__feature-dot"></span>
            趋势图表可视化
          </div>
        </div>
      </div>
    </section>

    <section class="login-page__panel login-page__form-wrap">
      <div class="login-card">
        <div class="login-card__heading">
          <p class="login-card__eyebrow">账号登录</p>
          <h2 class="login-card__title">进入工作台</h2>
        </div>

        <el-alert
          v-if="state.errorMessage"
          :title="state.errorMessage"
          :description="state.traceId ? `TraceID: ${state.traceId}` : ''"
          type="error"
          show-icon
          :closable="false"
        />

        <el-form class="login-card__form" @submit.prevent="handleSubmit">
          <el-form-item>
            <el-input
              v-model="form.username"
              placeholder="用户名"
              size="large"
              :prefix-icon="User"
              autocomplete="username"
              data-test="login-username"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              placeholder="密码"
              type="password"
              size="large"
              :prefix-icon="Lock"
              show-password
              autocomplete="current-password"
              data-test="login-password"
            />
          </el-form-item>
          <el-button
            class="login-card__submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="state.submitting"
            data-test="login-submit"
          >
            登录
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(199, 146, 92, 0.18), transparent 40%),
    radial-gradient(circle at bottom right, rgba(36, 61, 54, 0.12), transparent 36%),
    linear-gradient(180deg, var(--color-bg), var(--color-bg-secondary));
}

.login-page__panel {
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-page__hero {
  padding: 3rem;
  background:
    radial-gradient(circle at 20% 30%, rgba(199, 146, 92, 0.22), transparent 50%),
    linear-gradient(160deg, rgba(36, 61, 54, 0.06), transparent);
  position: relative;
  overflow: hidden;
}

.login-page__hero::before {
  content: '';
  position: absolute;
  top: -80px;
  right: -80px;
  width: 320px;
  height: 320px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(199, 146, 92, 0.15), transparent 70%);
  pointer-events: none;
}

.login-page__hero::after {
  content: '';
  position: absolute;
  bottom: -60px;
  left: -60px;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(36, 61, 54, 0.1), transparent 70%);
  pointer-events: none;
}

.login-page__hero-inner {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 1.5rem;
  max-width: 380px;
}

.login-page__eyebrow {
  margin: 0;
  color: var(--color-accent-strong);
  font-size: 0.78rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  font-weight: 700;
}

.login-page__headline {
  margin: 0;
  font-size: clamp(2.4rem, 4vw, 3.6rem);
  line-height: 1.1;
  font-family: var(--font-heading);
  color: var(--color-primary);
  letter-spacing: -0.01em;
}

.login-page__description {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 1.05rem;
  line-height: 1.85;
}

.login-page__features {
  display: grid;
  gap: 0.6rem;
  margin-top: 0.5rem;
}

.login-page__feature-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.login-page__feature-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-accent);
  flex-shrink: 0;
}

.login-page__form-wrap {
  padding: 2rem;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(8px);
}

.login-card {
  display: grid;
  gap: 1.5rem;
  width: min(100%, 420px);
  padding: 2.25rem;
  border: 1px solid rgba(35, 65, 58, 0.1);
  border-radius: 1.5rem;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: var(--shadow-soft);
}

.login-card__heading {
  display: grid;
  gap: 0.3rem;
}

.login-card__eyebrow {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.login-card__title {
  margin: 0;
  font-size: 1.6rem;
  font-family: var(--font-heading);
  color: var(--color-primary);
}

.login-card__form {
  display: grid;
  gap: 0.5rem;
}

.login-card__submit {
  width: 100%;
  margin-top: 0.25rem;
  height: 46px;
  font-size: 1rem;
  letter-spacing: 0.05em;
}

/* Mobile */
@media (max-width: 768px) {
  .login-page {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .login-page__hero {
    padding: 1.75rem 1.25rem;
    align-items: flex-start;
  }

  .login-page__hero-inner {
    gap: 0.75rem;
    max-width: 100%;
  }

  .login-page__headline {
    font-size: 2rem;
  }

  .login-page__features {
    display: none;
  }

  .login-page__description {
    font-size: 0.9rem;
  }

  .login-page__form-wrap {
    align-items: flex-start;
    padding: 1.5rem 1.25rem;
  }

  .login-card {
    width: 100%;
    padding: 1.5rem;
    border-radius: 1.25rem;
  }
}

/* Tablet */
@media (max-width: 900px) and (min-width: 769px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-page__hero {
    padding: 2rem;
    min-height: 220px;
  }
}
</style>
