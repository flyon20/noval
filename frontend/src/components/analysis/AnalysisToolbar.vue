<script setup lang="ts">
const props = defineProps<{
  running: boolean;
  disabling?: boolean;
  availableModels?: string[];
  modelName?: string;
}>();

const emit = defineEmits<{
  stop: [];
  rerun: [];
  copy: [];
  'update:modelName': [value: string];
}>();

function handleCopy() {
  emit('copy');
}

function handleModelChange(value: string) {
  emit('update:modelName', value);
}
</script>

<template>
  <div class="analysis-toolbar">
    <el-select
      v-if="props.availableModels && props.availableModels.length > 1"
      :model-value="props.modelName"
      class="analysis-toolbar__model-select"
      placeholder="选择模型"
      data-test="analysis-toolbar-model-select"
      @update:model-value="handleModelChange"
    >
      <el-option
        v-for="model in props.availableModels"
        :key="model"
        :label="model"
        :value="model"
      />
    </el-select>
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
  align-items: center;
}

.analysis-toolbar__model-select {
  width: 180px;
}

@media (max-width: 768px) {
  .analysis-toolbar__model-select {
    width: 100%;
  }
}
</style>
