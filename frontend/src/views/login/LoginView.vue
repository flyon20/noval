<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { Lock, User } from '@element-plus/icons-vue';
import { authApi } from '@/api/auth';
import { systemApi } from '@/api/system';
import { HOME_ROUTE } from '@/constants/auth';
import { DEFAULT_PLATFORM } from '@/constants/crawler';
import { getErrorPayload } from '@/lib/http-error';
import { useAuthStore } from '@/stores/auth';

type AuthMode = 'login' | 'register';

const PASSWORD_RULE_TEXT = '密码需至少 8 位，且包含大写字母、小写字母和数字';
const REGISTER_UNAVAILABLE_MESSAGE = '注册入口暂时不可用，请稍后重试';
const REGISTER_INVALID_MESSAGE = '注册信息有误，请检查用户名、密码和确认密码';
const LOGIN_INVALID_MESSAGE = '登录信息有误，请检查用户名和密码';
const AUTH_MESSAGE_MAP: Record<string, string> = {
  'username already exists': '用户名已存在，请更换后重试',
  'username or password is incorrect': '登录失败，请检查用户名和密码',
  'bad credentials': '登录失败，请检查用户名和密码',
  'password is required': '请输入密码',
  'username is required': '请输入用户名',
};

const router = useRouter();
const authStore = useAuthStore();

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
});

const state = reactive({
  mode: 'login' as AuthMode,
  submitting: false,
  errorMessage: '',
  traceId: '',
});

const isRegisterMode = computed(() => state.mode === 'register');
const usernamePlaceholder = computed(() => (
  isRegisterMode.value ? '创建用户名，用于后续登录' : '用户名'
));
const passwordPlaceholder = computed(() => (
  isRegisterMode.value ? '设置密码，至少 8 位，含大小写字母和数字' : '密码'
));
const confirmPasswordPlaceholder = '再次输入密码，需与上方一致';

function triggerLoginBootstrap() {
  try {
    void systemApi.loginBootstrap({ platform: DEFAULT_PLATFORM }).catch((error) => {
      console.warn('login bootstrap failed', error);
    });
  } catch (error) {
    console.warn('login bootstrap failed', error);
  }
}

function switchMode(mode: AuthMode) {
  if (state.submitting || state.mode === mode) {
    return;
  }

  state.mode = mode;
  state.errorMessage = '';
  state.traceId = '';
  form.password = '';
  form.confirmPassword = '';
}

function matchesPasswordRule(password: string) {
  return password.length >= 8
    && /[A-Z]/.test(password)
    && /[a-z]/.test(password)
    && /\d/.test(password);
}

function validateForm() {
  if (!form.username.trim() || !form.password) {
    state.errorMessage = '请输入用户名和密码';
    state.traceId = '';
    return false;
  }

  if (isRegisterMode.value && !matchesPasswordRule(form.password)) {
    state.errorMessage = PASSWORD_RULE_TEXT;
    state.traceId = '';
    return false;
  }

  if (isRegisterMode.value && form.password !== form.confirmPassword) {
    state.errorMessage = '两次输入的密码不一致';
    state.traceId = '';
    return false;
  }

  return true;
}

function resolveAuthErrorMessage(message: string | undefined, mode: AuthMode) {
  const normalized = (message ?? '').trim();

  if (!normalized) {
    return mode === 'register' ? '注册失败，请检查输入信息后重试' : '登录失败，请检查用户名和密码';
  }

  if (AUTH_MESSAGE_MAP[normalized]) {
    return AUTH_MESSAGE_MAP[normalized];
  }

  if (normalized === 'unauthorized') {
    return mode === 'register' ? REGISTER_UNAVAILABLE_MESSAGE : '登录失败，请检查用户名和密码';
  }

  if (normalized === 'invalid request parameter' || normalized === 'request body is invalid') {
    return mode === 'register' ? REGISTER_INVALID_MESSAGE : LOGIN_INVALID_MESSAGE;
  }

  if (/[\u4e00-\u9fa5]/.test(normalized)) {
    return normalized;
  }

  if (/^[\dA-Za-z_-]+$/.test(normalized)) {
    return mode === 'register' ? '注册失败，请检查输入信息后重试' : '登录失败，请检查用户名和密码';
  }

  return mode === 'register' ? '注册失败，请检查输入信息后重试' : '登录失败，请检查用户名和密码';
}

async function handleSubmit() {
  if (!validateForm()) {
    return;
  }

  state.submitting = true;
  state.errorMessage = '';
  state.traceId = '';

  try {
    const payload = {
      username: form.username.trim(),
      password: form.password,
    };
    const response = isRegisterMode.value
      ? await authApi.register(payload)
      : await authApi.login(payload);

    authStore.applyTokenResponse(response.data.data);
    triggerLoginBootstrap();
    await router.push(HOME_ROUTE);
  } catch (error) {
    const payload = getErrorPayload(error);
    state.errorMessage = resolveAuthErrorMessage(payload.message, state.mode);
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
        <div class="login-card__mode">
          <button
            class="login-card__mode-item"
            :class="{ 'is-active': !isRegisterMode }"
            type="button"
            data-test="auth-mode-login"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            class="login-card__mode-item"
            :class="{ 'is-active': isRegisterMode }"
            type="button"
            data-test="auth-mode-register"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <div class="login-card__heading">
          <p class="login-card__eyebrow">{{ isRegisterMode ? '快速注册' : '账号登录' }}</p>
          <h2 class="login-card__title">{{ isRegisterMode ? '创建新账号' : '进入工作台' }}</h2>
          <p class="login-card__subtitle">
            {{ isRegisterMode ? '当前为免验证码注册，注册成功后将自动进入系统。' : '使用已有账号直接进入工作台。' }}
          </p>
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
              :placeholder="usernamePlaceholder"
              size="large"
              :prefix-icon="User"
              autocomplete="username"
              data-test="login-username"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              :placeholder="passwordPlaceholder"
              type="password"
              size="large"
              :prefix-icon="Lock"
              show-password
              :autocomplete="isRegisterMode ? 'new-password' : 'current-password'"
              data-test="login-password"
            />
          </el-form-item>
          <el-form-item v-if="isRegisterMode">
            <el-input
              v-model="form.confirmPassword"
              :placeholder="confirmPasswordPlaceholder"
              type="password"
              size="large"
              :prefix-icon="Lock"
              show-password
              autocomplete="new-password"
              data-test="register-confirm-password"
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
            {{ isRegisterMode ? '注册并进入' : '登录' }}
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

.login-card__mode {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  padding: 0.35rem;
  border-radius: 999px;
  background: rgba(35, 65, 58, 0.06);
}

.login-card__mode-item {
  min-height: 42px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 160ms ease,
    color 160ms ease,
    box-shadow 160ms ease;
}

.login-card__mode-item.is-active {
  background: rgba(255, 255, 255, 0.96);
  color: var(--color-primary);
  box-shadow: 0 8px 20px rgba(35, 65, 58, 0.12);
}

.login-card__heading {
  display: grid;
  gap: 0.35rem;
}

.login-card__eyebrow,
.login-card__subtitle {
  margin: 0;
}

.login-card__eyebrow {
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

.login-card__subtitle {
  color: var(--color-text-muted);
  line-height: 1.7;
  font-size: 0.92rem;
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
