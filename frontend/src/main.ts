import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import { createPinia } from 'pinia';
import App from '@/App.vue';
import router from '@/router';
import { useAuthStore } from '@/stores/auth';
import '@/styles/element.scss';
import '@/styles/base.scss';
import { registerServiceWorker } from '@/pwa/register-sw';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

const authStore = useAuthStore(pinia);
await authStore.ensureAuthRestored();

app.use(router);
app.use(ElementPlus);
app.mount('#app');

registerServiceWorker();
