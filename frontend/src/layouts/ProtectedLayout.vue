<script setup lang="ts">
import { computed, KeepAlive, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { authApi } from '@/api/auth';
import { systemApi } from '@/api/system';
import TurnstileWidget from '@/components/auth/TurnstileWidget.vue';
import AppShell from '@/layouts/AppShell.vue';
import { getErrorPayload } from '@/lib/http-error';
import { useAuthStore } from '@/stores/auth';
import type { PasswordChangeRequest } from '@/types/auth';

type PasswordVerifyMode = 'OLD_PASSWORD' | 'SMS_CODE';

const TURNSTILE_LOAD_ERROR_MESSAGE = '人机校验组件加载失败，请刷新页面或更换网络后重试';

const router = useRouter();
const authStore = useAuthStore();
const passwordDialogVisible = ref(false);
const passwordSubmitting = ref(false);
const passwordSendingSms = ref(false);
const turnstileRef = ref<{ reset: () => void } | null>(null);
const passwordForm = reactive({
  verifyMode: 'OLD_PASSWORD' as PasswordVerifyMode,
  oldPassword: '',
  smsCode: '',
  smsOutId: '',
  newPassword: '',
  confirmPassword: '',
});
const passwordTurnstile = reactive({
  configLoaded: false,
  enabled: false,
  siteKey: '',
  token: '',
  unavailable: false,
});

const currentPhone = computed(() => authStore.session?.phone?.trim?.() ?? '');
const maskedCurrentPhone = computed(() => maskPhone(currentPhone.value));
const showPasswordTurnstile = computed(() => (
  passwordForm.verifyMode === 'SMS_CODE'
    && passwordTurnstile.enabled
    && !!passwordTurnstile.siteKey
));

async function handleLogout() {
  await authStore.logout();
  await router.replace('/login');
}

function maskPhone(phone: string) {
  if (!/^1\d{10}$/.test(phone)) {
    return phone || '当前账号';
  }
  return `${phone.slice(0, 3)}****${phone.slice(7)}`;
}

function clearPasswordTurnstile({ resetWidget = false }: { resetWidget?: boolean } = {}) {
  passwordTurnstile.token = '';
  passwordTurnstile.unavailable = false;
  if (resetWidget) {
    turnstileRef.value?.reset();
  }
}

async function loadPasswordTurnstileConfig() {
  if (passwordTurnstile.configLoaded) {
    return;
  }
  try {
    const response = await systemApi.getAuthPublicConfig();
    passwordTurnstile.enabled = response.data.data?.turnstileEnabled ?? false;
    passwordTurnstile.siteKey = response.data.data?.turnstileSiteKey?.trim?.() ?? '';
  } catch {
    passwordTurnstile.enabled = false;
    passwordTurnstile.siteKey = '';
  } finally {
    passwordTurnstile.configLoaded = true;
  }
}

function openPasswordDialog() {
  passwordForm.verifyMode = 'OLD_PASSWORD';
  passwordForm.oldPassword = '';
  passwordForm.smsCode = '';
  passwordForm.smsOutId = '';
  passwordForm.newPassword = '';
  passwordForm.confirmPassword = '';
  clearPasswordTurnstile();
  passwordDialogVisible.value = true;
  void loadPasswordTurnstileConfig();
}

function matchesPasswordRule(password: string) {
  return password.length >= 8
    && /[A-Z]/.test(password)
    && /[a-z]/.test(password)
    && /\d/.test(password);
}

function switchPasswordVerifyMode(mode: string | number | boolean | undefined) {
  if (mode !== 'OLD_PASSWORD' && mode !== 'SMS_CODE') {
    return;
  }
  passwordForm.verifyMode = mode;
  passwordForm.oldPassword = '';
  passwordForm.smsCode = '';
  passwordForm.smsOutId = '';
  clearPasswordTurnstile({ resetWidget: true });
  if (mode === 'SMS_CODE') {
    void loadPasswordTurnstileConfig();
  }
}

function handlePasswordTurnstileVerified(token: string) {
  passwordTurnstile.token = token.trim();
  passwordTurnstile.unavailable = false;
}

function handlePasswordTurnstileExpired() {
  clearPasswordTurnstile();
}

function handlePasswordTurnstileError() {
  clearPasswordTurnstile();
  passwordTurnstile.unavailable = true;
  ElMessage.error(TURNSTILE_LOAD_ERROR_MESSAGE);
}

async function sendPasswordSmsCode() {
  if (passwordSendingSms.value) {
    return;
  }
  await loadPasswordTurnstileConfig();
  if (!currentPhone.value) {
    ElMessage.warning('当前账号缺少手机号，无法发送验证码');
    return;
  }
  if (showPasswordTurnstile.value && passwordTurnstile.unavailable) {
    ElMessage.error(TURNSTILE_LOAD_ERROR_MESSAGE);
    return;
  }
  if (showPasswordTurnstile.value && !passwordTurnstile.token) {
    ElMessage.warning('请先完成人机校验');
    return;
  }

  passwordSendingSms.value = true;
  try {
    const response = await authApi.sendSmsCode({
      phone: currentPhone.value,
      bizType: 'RESET_PASSWORD',
      ...(passwordTurnstile.token ? { turnstileToken: passwordTurnstile.token } : {}),
    });
    passwordForm.smsOutId = response.data.data?.smsOutId?.trim?.() ?? '';
    ElMessage.success('验证码已发送');
  } catch (error) {
    const payload = getErrorPayload(error);
    ElMessage.error(payload.message ?? '验证码发送失败，请稍后重试');
    clearPasswordTurnstile({ resetWidget: true });
  } finally {
    passwordSendingSms.value = false;
  }
}

async function submitPasswordChange() {
  if (passwordForm.verifyMode === 'OLD_PASSWORD' && !passwordForm.oldPassword) {
    ElMessage.warning('请输入原密码');
    return;
  }
  if (passwordForm.verifyMode === 'SMS_CODE' && !passwordForm.smsCode.trim()) {
    ElMessage.warning('请输入短信验证码');
    return;
  }
  if (!passwordForm.newPassword) {
    ElMessage.warning('请输入新密码');
    return;
  }
  if (!matchesPasswordRule(passwordForm.newPassword)) {
    ElMessage.warning('新密码至少 8 位，并包含大小写字母和数字');
    return;
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }

  passwordSubmitting.value = true;
  try {
    const payload: PasswordChangeRequest = passwordForm.verifyMode === 'SMS_CODE'
      ? {
          verifyMode: 'SMS_CODE',
          smsCode: passwordForm.smsCode.trim(),
          smsOutId: passwordForm.smsOutId || undefined,
          newPassword: passwordForm.newPassword,
        }
      : {
          verifyMode: 'OLD_PASSWORD',
          oldPassword: passwordForm.oldPassword,
          newPassword: passwordForm.newPassword,
        };

    await authApi.changePassword(payload);
    passwordDialogVisible.value = false;
    ElMessage.success('密码已修改');
  } finally {
    passwordSubmitting.value = false;
  }
}

onMounted(() => {
  void loadPasswordTurnstileConfig();
});
</script>

<template>
  <AppShell
    :roles="authStore.session?.roles ?? []"
    :username="authStore.session?.username ?? '未登录'"
    @change-password="openPasswordDialog"
    @logout="handleLogout"
  >
    <RouterView v-slot="{ Component }">
      <KeepAlive include="KnowledgeChatView">
        <component :is="Component" />
      </KeepAlive>
    </RouterView>
  </AppShell>

  <el-dialog
    v-model="passwordDialogVisible"
    title="修改密码"
    width="420px"
    class="password-change-dialog"
    align-center
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="验证方式">
        <el-radio-group
          :model-value="passwordForm.verifyMode"
          data-test="password-mode"
          @update:model-value="switchPasswordVerifyMode"
        >
          <el-radio-button value="OLD_PASSWORD">原密码</el-radio-button>
          <el-radio-button value="SMS_CODE" data-test="password-mode-sms">短信验证码</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="passwordForm.verifyMode === 'OLD_PASSWORD'" label="原密码">
        <el-input
          v-model="passwordForm.oldPassword"
          type="password"
          autocomplete="current-password"
          show-password
          data-test="password-old"
        />
      </el-form-item>

      <template v-else>
        <el-form-item label="当前手机号">
          <div class="password-change-dialog__phone">{{ maskedCurrentPhone }}</div>
        </el-form-item>
        <el-form-item v-if="showPasswordTurnstile" label="人机校验">
          <TurnstileWidget
            ref="turnstileRef"
            :site-key="passwordTurnstile.siteKey"
            @verified="handlePasswordTurnstileVerified"
            @expired="handlePasswordTurnstileExpired"
            @error="handlePasswordTurnstileError"
            @timeout="handlePasswordTurnstileError"
            @unsupported="handlePasswordTurnstileError"
          />
        </el-form-item>
        <el-form-item label="短信验证码">
          <div class="password-change-dialog__sms-row">
            <el-input
              v-model="passwordForm.smsCode"
              placeholder="请输入验证码"
              data-test="password-sms-code"
            />
            <el-button
              :loading="passwordSendingSms"
              data-test="password-send-sms"
              @click="sendPasswordSmsCode"
            >
              发送验证码
            </el-button>
          </div>
        </el-form-item>
      </template>

      <el-form-item label="新密码">
        <el-input
          v-model="passwordForm.newPassword"
          type="password"
          autocomplete="new-password"
          show-password
          data-test="password-new"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="passwordForm.confirmPassword"
          type="password"
          autocomplete="new-password"
          show-password
          data-test="password-confirm"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="passwordDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="passwordSubmitting" data-test="password-submit" @click="submitPasswordChange">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.password-change-dialog) {
  max-width: calc(100vw - 32px);
}

.password-change-dialog__phone {
  width: 100%;
  color: var(--el-text-color-primary);
  font-size: 0.95rem;
}

.password-change-dialog__sms-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.75rem;
  width: 100%;
}

@media (max-width: 520px) {
  .password-change-dialog__sms-row {
    grid-template-columns: 1fr;
  }
}
</style>
