<template>
  <el-select
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template v-for="group in optionGroups" :key="group.label || '__flat__'">
      <template v-if="group.label">
        <el-option-group :label="group.label">
          <el-option
            v-for="option in group.options"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          >
            {{ option.optionLabel || option.label }}
          </el-option>
        </el-option-group>
      </template>
      <template v-else>
        <el-option
          v-for="option in group.options"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </template>
    </template>
  </el-select>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { groupModelOptions, type SelectableModelOption } from '@/utils/llmModelOptions'

const props = defineProps<{
  modelValue: string
  options: readonly SelectableModelOption[]
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()

const optionGroups = computed(() => groupModelOptions(props.options))
</script>
