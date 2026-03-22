<script setup lang="ts">
import { reactive } from 'vue';
import { useRouter } from 'vue-router';
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
    try {
      await systemApi.loginBootstrap({ platform: DEFAULT_PLATFORM });
    } catch (error) {
      console.warn('login bootstrap failed', error);
    }
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
      <p class="login-page__eyebrow">NOVAL</p>
      <h1>登录页</h1>
      <p class="login-page__description">
        登录后进入控制台。
      </p>
    </section>

    <section class="login-page__panel login-page__form-wrap">
      <div class="login-card">
        <div class="login-card__heading">
          <p>账号登录</p>
          <h2>进入控制台</h2>
        </div>

        <el-alert
          v-if="state.errorMessage"
          :closable="false"
          :description="state.traceId ? `traceId: ${state.traceId}` : undefined"
          :title="state.errorMessage"
          type="error"
        />

        <el-form class="login-card__form" label-position="top" @submit.prevent="handleSubmit">
          <el-form-item label="用户名" required>
            <el-input
              v-model="form.username"
              autocomplete="username"
              placeholder="请输入用户名"
            />
          </el-form-item>

          <el-form-item label="密码" required>
            <el-input
              v-model="form.password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              type="password"
            />
          </el-form-item>

          <el-button
            class="login-card__submit"
            :loading="state.submitting"
            native-type="submit"
            type="primary"
          >
            登录进入
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 1.5rem;
  min-height: 100vh;
  padding: 1.25rem;
}

.login-page__panel {
  border: 1px solid var(--color-border);
  border-radius: 1.5rem;
  box-shadow: var(--shadow-soft);
}

.login-page__hero {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1.1rem;
  padding: 3rem;
  background:
    radial-gradient(circle at top right, rgba(210, 136, 61, 0.18), transparent 32%),
    linear-gradient(180deg, rgba(255, 252, 245, 0.96), rgba(244, 239, 229, 0.92));
}

.login-page__eyebrow,
.login-page__description,
.login-card__heading p {
  margin: 0;
  color: var(--color-text-muted);
}

.login-page__hero h1,
.login-card__heading h2 {
  margin: 0;
}

.login-page__hero h1 {
  max-width: 10ch;
  font-size: clamp(2.3rem, 4vw, 4rem);
  line-height: 1.08;
}

.login-page__description {
  max-width: 34ch;
  line-height: 1.8;
}

.login-page__form-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  background: rgba(255, 255, 255, 0.72);
}

.login-card {
  display: grid;
  gap: 1.25rem;
  width: min(100%, 460px);
  padding: 2rem;
  border: 1px solid rgba(35, 65, 58, 0.12);
  border-radius: 1.35rem;
  background: rgba(255, 255, 255, 0.92);
}

.login-card__form {
  display: grid;
  gap: 0.75rem;
}

.login-card__submit {
  width: 100%;
  margin-top: 0.5rem;
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-page__hero {
    padding: 2rem;
  }
}
</style>
