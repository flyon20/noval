<script setup lang="ts">
import { useRouter } from 'vue-router';
import AppShell from '@/layouts/AppShell.vue';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const authStore = useAuthStore();

async function handleLogout() {
  await authStore.logout();
  await router.replace('/login');
}
</script>

<template>
  <AppShell
    :roles="authStore.session?.roles ?? []"
    :username="authStore.session?.username ?? '未登录'"
    @logout="handleLogout"
  >
    <RouterView />
  </AppShell>
</template>
