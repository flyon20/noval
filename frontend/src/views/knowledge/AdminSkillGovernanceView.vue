<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type { SkillCandidate } from '@/types/knowledge';

defineOptions({
  name: 'AdminSkillGovernanceView',
});

const candidates = ref<SkillCandidate[]>([]);
const loading = ref(false);

onMounted(loadCandidates);

async function loadCandidates() {
  loading.value = true;
  try {
    const response = await knowledgeApi.listSkillCandidates();
    candidates.value = response.data.data ?? [];
  } finally {
    loading.value = false;
  }
}

async function review(candidate: SkillCandidate, decision: 'APPROVED' | 'REJECTED') {
  const response = await knowledgeApi.reviewSkillCandidate(candidate.id, { decision });
  const updated = response.data.data;
  candidates.value = candidates.value.map((item) => (item.id === updated.id ? updated : item));
}
</script>

<template>
  <main class="admin-skill-governance">
    <el-table v-loading="loading" :data="candidates" size="small">
      <el-table-column prop="skillId" label="Skill" min-width="160" />
      <el-table-column prop="title" label="Title" min-width="180" />
      <el-table-column prop="status" label="Status" width="120" />
      <el-table-column prop="evalStatus" label="Eval" width="120" />
      <el-table-column label="Actions" width="180">
        <template #default="{ row }">
          <el-button size="small" data-test="approve-skill" @click="review(row, 'APPROVED')">
            Approve
          </el-button>
          <el-button size="small" data-test="reject-skill" @click="review(row, 'REJECTED')">
            Reject
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </main>
</template>

<style scoped>
.admin-skill-governance {
  padding: 1rem;
}
</style>
