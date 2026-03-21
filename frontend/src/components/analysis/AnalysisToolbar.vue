<script setup lang="ts">
const props = defineProps<{
  running: boolean;
  disabling?: boolean;
}>();

const emit = defineEmits<{
  stop: [];
  rerun: [];
  copy: [];
}>();

function handleCopy() {
  emit('copy');
}
</script>

<template>
  <div class="analysis-toolbar">
    <el-button
      plain
      type="warning"
      native-type="button"
      :disabled="!props.running"
      data-test="analysis-toolbar-stop"
      @click="$emit('stop')"
    >
      停止生成
    </el-button>
    <el-button
      type="primary"
      native-type="button"
      :loading="props.running"
      :disabled="props.disabling"
      data-test="analysis-toolbar-rerun"
      @click="$emit('rerun')"
    >
      重新生成
    </el-button>
    <el-button plain native-type="button" data-test="analysis-toolbar-copy" @click="handleCopy">
      复制结果
    </el-button>
  </div>
</template>

<style scoped lang="scss">
.analysis-toolbar {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}
</style>
