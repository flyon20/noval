<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Iphone, Lock, Message, RefreshRight } from '@element-plus/icons-vue';
import { authApi } from '@/api/auth';
import { systemApi } from '@/api/system';
import { HOME_ROUTE } from '@/constants/auth';
import { getErrorPayload } from '@/lib/http-error';
import { useAuthStore } from '@/stores/auth';
import TurnstileWidget from '@/components/auth/TurnstileWidget.vue';
import ElasticMeshBackground from '@/components/auth/ElasticMeshBackground.vue';
import { useXpbdSelector } from '@/composables/useXpbdSelector';

type AuthMode = 'password-login' | 'sms-login' | 'register' | 'reset-password';

const PASSWORD_RULE_TEXT = '密码需至少 8 位，且包含大写字母、小写字母和数字';
const PHONE_RULE = /^1\d{10}$/;
const SMS_COUNTDOWN_SECONDS = 60;
const AUTH_MESSAGE_MAP: Record<string, string> = {
  'password is required': '请输入密码',
  'phone is required': '请输入手机号',
  'smsCode is required': '请输入短信验证码',
  'newPassword is required': '请输入新密码',
  '验证码发送过于频繁，请稍后再试': '验证码发送过于频繁，请稍后再试',
  '手机号未注册，请先注册': '手机号未注册，请先注册',
  '手机号已注册，请直接登录': '手机号已注册，请直接登录',
  '验证码错误或已失效': '验证码错误或已失效',
};

const router = useRouter();
const authStore = useAuthStore();

const form = reactive({
  phone: '',
  password: '',
  smsCode: '',
  confirmPassword: '',
});

const state = reactive({
  mode: 'password-login' as AuthMode,
  submitting: false,
  sendingSms: false,
  smsCountdown: 0,
  turnstileConfigLoaded: false,
  turnstileEnabled: false,
  turnstileSiteKey: '',
  turnstileToken: '',
  turnstileUnavailable: false,
  debugVerifyCode: '',
  smsOutId: '',
  errorMessage: '',
  traceId: '',
});

const TURNSTILE_REQUIRED_MESSAGE = '请完成人机校验后再发送验证码';
const TURNSTILE_LOAD_ERROR_MESSAGE = '人机校验组件加载失败，请刷新页面或更换网络后重试';

const countdownTimer = ref<ReturnType<typeof setInterval> | null>(null);
const turnstileRef = ref<{ reset: () => void } | null>(null);
const lastTurnstilePhone = ref('');

const isPasswordLoginMode = computed(() => state.mode === 'password-login');
const isSmsLoginMode = computed(() => state.mode === 'sms-login');
const isRegisterMode = computed(() => state.mode === 'register');
const isResetMode = computed(() => state.mode === 'reset-password');
const showPasswordField = computed(() => !isSmsLoginMode.value);
const showSmsCodeField = computed(() => isSmsLoginMode.value || isRegisterMode.value || isResetMode.value);
const showConfirmPasswordField = computed(() => isRegisterMode.value || isResetMode.value);
const submitLabel = computed(() => {
  if (isRegisterMode.value) {
    return '注册并进入';
  }
  if (isResetMode.value) {
    return '重置密码';
  }
  return '登录';
});
const headingLabel = computed(() => {
  if (isRegisterMode.value) {
    return '手机号注册';
  }
  if (isSmsLoginMode.value) {
    return '验证码登录';
  }
  if (isResetMode.value) {
    return '重置密码';
  }
  return '账号登录';
});
const titleLabel = computed(() => {
  if (isRegisterMode.value) {
    return '创建新账号';
  }
  if (isSmsLoginMode.value) {
    return '短信快速登录';
  }
  if (isResetMode.value) {
    return '找回账号密码';
  }
  return '进入工作台';
});
const phonePlaceholder = computed(() => (
  isRegisterMode.value ? '请输入手机号完成注册' : '手机号'
));
const passwordPlaceholder = computed(() => {
  if (isRegisterMode.value) {
    return '设置密码，至少 8 位，含大小写字母和数字';
  }
  if (isResetMode.value) {
    return '输入新密码';
  }
  return '密码';
});
const smsButtonLabel = computed(() => (
  state.smsCountdown > 0 ? `${state.smsCountdown}s 后重试` : '发送验证码'
));
const smsBizType = computed<'REGISTER' | 'LOGIN' | 'RESET_PASSWORD'>(() => {
  if (isRegisterMode.value) {
    return 'REGISTER';
  }
  if (isResetMode.value) {
    return 'RESET_PASSWORD';
  }
  return 'LOGIN';
});
const showTurnstile = computed(() => showSmsCodeField.value && state.turnstileEnabled && !!state.turnstileSiteKey);
const authModeIndex = computed(() => {
  if (isPasswordLoginMode.value) {
    return 0;
  }
  if (isRegisterMode.value) {
    return 1;
  }
  return -1;
});
const { position: authModePosition } = useXpbdSelector(authModeIndex);
const authModeIndicatorStyle = computed(() => ({
  '--auth-mode-position': String(authModePosition.value),
  '--auth-mode-opacity': authModeIndex.value >= 0 ? '1' : '0',
}));

function resetTransientState() {
  state.debugVerifyCode = '';
  state.errorMessage = '';
  state.traceId = '';
}

function stopCountdown() {
  if (countdownTimer.value) {
    clearInterval(countdownTimer.value);
    countdownTimer.value = null;
  }
}

function startCountdown() {
  stopCountdown();
  state.smsCountdown = SMS_COUNTDOWN_SECONDS;
  countdownTimer.value = setInterval(() => {
    state.smsCountdown -= 1;
    if (state.smsCountdown <= 0) {
      stopCountdown();
      state.smsCountdown = 0;
    }
  }, 1000);
}

function switchMode(mode: AuthMode) {
  if (state.submitting || state.mode === mode) {
    return;
  }

  state.mode = mode;
  resetTransientState();
  form.password = '';
  form.smsCode = '';
  form.confirmPassword = '';
  clearTurnstileVerification();
  state.smsOutId = '';
}

function clearTurnstileVerification({ resetWidget = false }: { resetWidget?: boolean } = {}) {
  state.turnstileToken = '';
  state.turnstileUnavailable = false;
  lastTurnstilePhone.value = '';
  if (resetWidget) {
    turnstileRef.value?.reset();
  }
}

function matchesPasswordRule(password: string) {
  return password.length >= 8
    && /[A-Z]/.test(password)
    && /[a-z]/.test(password)
    && /\d/.test(password);
}

function validatePhone() {
  const phone = form.phone.trim();
  if (!phone) {
    state.errorMessage = '请输入手机号';
    return false;
  }
  if (!PHONE_RULE.test(phone)) {
    state.errorMessage = '请输入正确的 11 位手机号';
    return false;
  }
  return true;
}

function validateForm() {
  resetTransientState();
  if (!validatePhone()) {
    return false;
  }

  if (showSmsCodeField.value && !form.smsCode.trim()) {
    state.errorMessage = '请输入短信验证码';
    return false;
  }

  if (showPasswordField.value && !form.password) {
    state.errorMessage = isResetMode.value ? '请输入新密码' : '请输入密码';
    return false;
  }

  if ((isRegisterMode.value || isResetMode.value) && !matchesPasswordRule(form.password)) {
    state.errorMessage = PASSWORD_RULE_TEXT;
    return false;
  }

  if (showConfirmPasswordField.value && form.password !== form.confirmPassword) {
    state.errorMessage = '两次输入的密码不一致';
    return false;
  }

  return true;
}

function resolveAuthErrorMessage(message: string | undefined, mode: AuthMode) {
  const normalized = (message ?? '').trim();
  if (!normalized) {
    return mode === 'register' ? '注册失败，请检查输入信息后重试' : '操作失败，请稍后重试';
  }
  if (AUTH_MESSAGE_MAP[normalized]) {
    return AUTH_MESSAGE_MAP[normalized];
  }
  if (/[\u4e00-\u9fa5]/.test(normalized)) {
    return normalized;
  }
  if (mode === 'register') {
    return '注册失败，请检查输入信息后重试';
  }
  if (mode === 'reset-password') {
    return '重置密码失败，请稍后重试';
  }
  return '登录失败，请检查手机号和密码';
}

function getDeviceLabel() {
  if (typeof navigator === 'undefined') {
    return undefined;
  }

  const platform = navigator.platform?.trim() || 'Unknown OS';
  const userAgent = navigator.userAgent?.trim();

  if (!userAgent) {
    return platform;
  }

  const browserMatch = userAgent.match(/(Edg|Chrome|Firefox|Safari)\/[\d.]+/);
  const browserLabel = browserMatch ? browserMatch[0] : 'Browser';

  return `${browserLabel} on ${platform}`;
}

async function sendSmsCode() {
  resetTransientState();
  if (!state.turnstileConfigLoaded) {
    await loadAuthPublicConfig();
  }
  const normalizedPhone = form.phone.trim();
  if (!validatePhone() || state.sendingSms || state.smsCountdown > 0) {
    return;
  }
  if (showTurnstile.value && state.turnstileUnavailable) {
    state.errorMessage = TURNSTILE_LOAD_ERROR_MESSAGE;
    return;
  }
  if (showTurnstile.value && lastTurnstilePhone.value && lastTurnstilePhone.value !== normalizedPhone) {
    clearTurnstileVerification({ resetWidget: true });
  }
  if (showTurnstile.value && !state.turnstileToken) {
    state.errorMessage = TURNSTILE_REQUIRED_MESSAGE;
    return;
  }

  state.sendingSms = true;
  try {
    const response = await authApi.sendSmsCode({
      phone: normalizedPhone,
      bizType: smsBizType.value,
      ...(state.turnstileToken ? { turnstileToken: state.turnstileToken } : {}),
    });
    state.debugVerifyCode = response.data.data?.debugVerifyCode?.trim?.() ?? '';
    state.smsOutId = response.data.data?.smsOutId?.trim?.() ?? '';
    lastTurnstilePhone.value = normalizedPhone;
    startCountdown();
  } catch (error) {
    const payload = getErrorPayload(error);
    state.errorMessage = resolveAuthErrorMessage(payload.message, state.mode);
    state.traceId = payload.traceId ?? '';
    if (state.errorMessage.includes('人机校验')) {
      clearTurnstileVerification({ resetWidget: true });
    }
  } finally {
    state.sendingSms = false;
  }
}

async function loadAuthPublicConfig() {
  try {
    const response = await systemApi.getAuthPublicConfig();
    state.turnstileEnabled = response.data.data?.turnstileEnabled ?? false;
    state.turnstileSiteKey = response.data.data?.turnstileSiteKey?.trim?.() ?? '';
  } catch {
    state.turnstileEnabled = false;
    state.turnstileSiteKey = '';
  } finally {
    state.turnstileConfigLoaded = true;
  }
}

function handleTurnstileVerified(token: string) {
  state.turnstileToken = token.trim();
  state.turnstileUnavailable = false;
  lastTurnstilePhone.value = form.phone.trim();
}

function handleTurnstileExpired() {
  clearTurnstileVerification();
}

function handleTurnstileError() {
  clearTurnstileVerification();
  state.turnstileUnavailable = true;
  state.errorMessage = TURNSTILE_LOAD_ERROR_MESSAGE;
}

watch(() => form.phone.trim(), (nextPhone, previousPhone) => {
  if (!showTurnstile.value) {
    return;
  }
  if (!previousPhone || nextPhone === previousPhone) {
    return;
  }
  if (!lastTurnstilePhone.value) {
    return;
  }
  if (nextPhone !== lastTurnstilePhone.value) {
    clearTurnstileVerification({ resetWidget: true });
  }
});

onMounted(() => {
  void loadAuthPublicConfig();
});

onBeforeUnmount(() => {
  stopCountdown();
});

async function handleSubmit() {
  if (!validateForm()) {
    return;
  }

  state.submitting = true;

  try {
    if (isPasswordLoginMode.value) {
      const response = await authApi.login({
        phone: form.phone.trim(),
        password: form.password,
        deviceLabel: getDeviceLabel(),
      });
      authStore.applyTokenResponse(response.data.data);
      await router.push(HOME_ROUTE);
      return;
    }

    if (isSmsLoginMode.value) {
      const response = await authApi.loginWithSms({
        phone: form.phone.trim(),
        smsCode: form.smsCode.trim(),
        smsOutId: state.smsOutId || undefined,
        deviceLabel: getDeviceLabel(),
      });
      authStore.applyTokenResponse(response.data.data);
      await router.push(HOME_ROUTE);
      return;
    }

    if (isRegisterMode.value) {
      const response = await authApi.register({
        phone: form.phone.trim(),
        smsCode: form.smsCode.trim(),
        smsOutId: state.smsOutId || undefined,
        password: form.password,
      });
      authStore.applyTokenResponse(response.data.data);
      await router.push(HOME_ROUTE);
      return;
    }

    await authApi.resetPassword({
      phone: form.phone.trim(),
      smsCode: form.smsCode.trim(),
      smsOutId: state.smsOutId || undefined,
      newPassword: form.password,
    });
    state.mode = 'password-login';
    resetTransientState();
    form.password = '';
    form.smsCode = '';
    form.confirmPassword = '';
    form.phone = '';
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
    <ElasticMeshBackground class="login-page__mesh" />
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
        <div class="login-card__mode" :style="authModeIndicatorStyle">
          <span class="login-card__mode-indicator" aria-hidden="true"></span>
          <button
            class="login-card__mode-item"
            :class="{ 'is-active': isPasswordLoginMode }"
            type="button"
            data-test="auth-mode-login"
            @click="switchMode('password-login')"
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
          <p class="login-card__eyebrow">{{ headingLabel }}</p>
          <h2 class="login-card__title">{{ titleLabel }}</h2>
        </div>

        <div class="login-card__quick-actions">
          <button
            class="login-card__link"
            type="button"
            data-test="auth-mode-sms-login"
            @click="switchMode('sms-login')"
          >
            <Iphone class="login-card__link-icon" />
            手机验证码登录
          </button>
          <button
            class="login-card__link"
            type="button"
            data-test="auth-mode-reset"
            @click="switchMode('reset-password')"
          >
            <RefreshRight class="login-card__link-icon" />
            忘记密码
          </button>
        </div>

        <el-alert
          v-if="state.errorMessage"
          :title="state.errorMessage"
          :description="state.traceId ? `TraceID: ${state.traceId}` : ''"
          type="error"
          show-icon
          :closable="false"
        />

        <el-alert
          v-else-if="state.debugVerifyCode"
          title="本地调试验证码"
          :description="state.debugVerifyCode"
          type="info"
          show-icon
          :closable="false"
          data-test="sms-debug-code"
        />

        <el-form class="login-card__form" @submit.prevent="handleSubmit">
          <el-form-item>
            <el-input
              v-model="form.phone"
              :placeholder="phonePlaceholder"
              size="large"
              :prefix-icon="Iphone"
              autocomplete="tel"
              data-test="login-phone"
            />
          </el-form-item>

          <el-form-item v-if="showSmsCodeField">
            <div class="login-card__sms-row">
              <el-input
                v-model="form.smsCode"
                placeholder="短信验证码"
                size="large"
                :prefix-icon="Message"
                autocomplete="one-time-code"
                data-test="login-sms-code"
              />
              <el-button
                class="login-card__sms-button"
                type="primary"
                plain
                :loading="state.sendingSms"
                :disabled="state.smsCountdown > 0"
                data-test="send-sms-code"
                @click.prevent="sendSmsCode"
              >
                {{ smsButtonLabel }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item v-if="showTurnstile">
            <TurnstileWidget
              ref="turnstileRef"
              :site-key="state.turnstileSiteKey"
              @verified="handleTurnstileVerified"
              @expired="handleTurnstileExpired"
              @error="handleTurnstileError"
              @timeout="handleTurnstileError"
              @unsupported="handleTurnstileError"
            />
          </el-form-item>

          <el-form-item v-if="showPasswordField">
            <el-input
              v-model="form.password"
              :placeholder="passwordPlaceholder"
              type="password"
              size="large"
              :prefix-icon="Lock"
              show-password
              :autocomplete="isPasswordLoginMode ? 'current-password' : 'new-password'"
              data-test="login-password"
            />
          </el-form-item>

          <el-form-item v-if="showConfirmPasswordField">
            <el-input
              v-model="form.confirmPassword"
              placeholder="再次输入密码，需与上方一致"
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
            {{ submitLabel }}
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
  max-width: 100%;
  overflow-x: clip;
  position: relative;
  isolation: isolate;
  user-select: none;
  background: linear-gradient(135deg, var(--color-bg), var(--color-bg-secondary) 58%, color-mix(in srgb, var(--color-primary-soft) 46%, var(--color-bg-secondary)));
}

.login-page__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 1;
}

.login-page__hero {
  padding: 3rem;
  background:
    linear-gradient(145deg, color-mix(in srgb, var(--color-primary-soft) 42%, transparent), transparent 54%),
    linear-gradient(320deg, color-mix(in srgb, var(--color-accent) 10%, transparent), transparent 58%),
    linear-gradient(160deg, color-mix(in srgb, var(--color-surface) 10%, transparent), transparent);
  position: relative;
  overflow: hidden;
}

.login-page__mesh {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.login-page :deep(input),
.login-page :deep(textarea) {
  user-select: text;
}

.login-page__hero::before {
  content: '';
  position: absolute;
  top: -80px;
  right: -80px;
  width: 320px;
  height: 320px;
  border-radius: 50%;
  background: linear-gradient(135deg, color-mix(in srgb, var(--color-secondary) 20%, transparent), transparent 70%);
  display: none;
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
  background: linear-gradient(315deg, color-mix(in srgb, var(--color-accent) 18%, transparent), transparent 70%);
  display: none;
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
  font-size: 3.4rem;
  line-height: 1.1;
  font-family: var(--font-heading);
  color: var(--color-primary);
  letter-spacing: 0;
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
  background: color-mix(in srgb, var(--color-glass) 16%, transparent);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.login-card {
  display: grid;
  gap: 1.25rem;
  width: min(100%, 420px);
  padding: 2.25rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 1.5rem;
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.22), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 94%, transparent);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(20px) saturate(1.14);
  -webkit-backdrop-filter: blur(20px) saturate(1.14);
  animation: login-card-settle 480ms var(--motion-spring) both;
}

@keyframes login-card-settle {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.99);
  }

  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.login-card__mode {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  padding: 0.35rem;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-glass) 72%, transparent);
  backdrop-filter: blur(14px) saturate(1.12);
  -webkit-backdrop-filter: blur(14px) saturate(1.12);
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  box-shadow: var(--shadow-card);
  position: relative;
  overflow: hidden;
}

.login-card__mode-indicator {
  position: absolute;
  z-index: 0;
  top: 0.35rem;
  bottom: 0.35rem;
  left: 0.35rem;
  width: calc((100% - 1.2rem) / 2);
  border: 1px solid color-mix(in srgb, var(--color-accent) 28%, transparent);
  border-radius: 999px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.24), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-glass) 92%, transparent);
  box-shadow: var(--shadow-glow);
  opacity: var(--auth-mode-opacity);
  pointer-events: none;
  transform: translate3d(calc(var(--auth-mode-position) * (100% + 0.5rem)), 0, 0);
  transition: opacity var(--motion-fast) ease;
  will-change: transform;
}

.login-card__mode-item {
  min-height: 42px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-glass) 38%, transparent);
  color: var(--color-text-muted);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
  position: relative;
  z-index: 1;
  transition: transform var(--motion-fast) var(--motion-spring),
    background var(--motion-fast) ease, color var(--motion-fast) ease,
    box-shadow var(--motion-fast) ease;
}

.login-card__mode-item:active,
.login-card__link:active {
  transform: scale(var(--motion-press-scale));
}

.login-card__mode-item.is-active {
  background: transparent;
  border-color: transparent;
  color: var(--color-primary);
  box-shadow: none;
}

.login-card__heading {
  display: grid;
  gap: 0.35rem;
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

.login-card__quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.8rem;
}

.login-card__link {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-accent-strong);
  cursor: pointer;
  font: inherit;
  transition: color var(--motion-fast) ease, transform var(--motion-fast) var(--motion-spring);
}

.login-card__link-icon {
  width: 1rem;
  height: 1rem;
}

.login-card__form {
  display: grid;
  gap: 0.5rem;
}

.login-card__sms-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.75rem;
  width: 100%;
}

.login-card__sms-button {
  min-width: 118px;
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
    grid-template-rows: auto auto;
    grid-template-rows: auto minmax(0, 1fr);
    min-height: 100dvh;
  }

  .login-page__hero {
    padding: 1.5rem 1.25rem 0.75rem;
    align-items: flex-start;
    background:
      linear-gradient(145deg, color-mix(in srgb, var(--color-primary-soft) 24%, transparent), transparent 62%),
      linear-gradient(320deg, color-mix(in srgb, var(--color-accent) 7%, transparent), transparent 64%),
      transparent;
  }

  .login-page__hero-inner {
    gap: 0.55rem;
    max-width: 100%;
  }

  .login-page__headline {
    font-size: 1.8rem;
  }

  .login-page__features {
    display: none;
  }

  .login-page__description {
    font-size: 0.86rem;
    line-height: 1.65;
  }

  .login-page__form-wrap {
    align-items: flex-start;
    min-height: 0;
    padding: 0.75rem 1rem calc(1.25rem + env(safe-area-inset-bottom));
    background: color-mix(in srgb, var(--color-glass) 8%, transparent);
    backdrop-filter: blur(4px);
    -webkit-backdrop-filter: blur(4px);
  }

  .login-card {
    width: 100%;
    padding: 1.35rem;
    border-radius: 1.25rem;
  }

  .login-card__mode-item {
    min-height: 44px;
  }

  .login-card__sms-row {
    grid-template-columns: 1fr;
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

@media (max-height: 520px) and (orientation: landscape) and (max-width: 900px) {
  .login-page {
    grid-template-columns: minmax(280px, 0.85fr) minmax(380px, 1.15fr);
    grid-template-rows: 1fr;
    min-height: 100dvh;
    height: 100dvh;
    overflow: hidden;
  }

  .login-page__hero {
    min-height: 0;
    padding: 1.25rem 1.75rem;
    align-items: center;
  }

  .login-page__hero-inner {
    gap: 0.45rem;
    max-width: 320px;
  }

  .login-page__headline {
    font-size: 2.15rem;
  }

  .login-page__description {
    font-size: 0.84rem;
    line-height: 1.5;
  }

  .login-page__features {
    display: none;
  }

  .login-page__form-wrap {
    height: 100dvh;
    padding: 0.75rem 1.25rem;
    overflow-y: auto;
  }

  .login-card {
    gap: 0.7rem;
    padding: 1rem 1.15rem;
    margin: auto 0;
  }

  .login-card__heading {
    gap: 0.1rem;
  }

  .login-card__title {
    font-size: 1.3rem;
  }

  .login-card__form {
    gap: 0.25rem;
  }

  .login-card__form :deep(.el-form-item) {
    margin-bottom: 0.35rem;
  }
}
</style>
