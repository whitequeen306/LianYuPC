<template>
  <div class="square-comment-input" @click.stop>
    <template v-if="editing">
      <el-input
        v-model="draft"
        class="comment-field"
        size="small"
        :maxlength="60"
        show-word-limit
        :placeholder="t('characterSquare.commentPlaceholder')"
        @keyup.enter="submit"
      />
      <div class="comment-actions">
        <el-button size="small" text @click="cancelEdit">{{ t('common.cancel') }}</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="submit">
          {{ t('characterSquare.commentSend') }}
        </el-button>
        <el-button
          v-if="mineComment"
          size="small"
          text
          type="danger"
          :loading="deleting"
          @click="remove"
        >
          {{ t('characterSquare.commentDelete') }}
        </el-button>
      </div>
    </template>
    <button
      v-else
      type="button"
      class="comment-trigger"
      @click="openEdit"
    >
      <template v-if="mineComment">
        {{ t('characterSquare.myComment', { text: mineComment.content }) }}
      </template>
      <template v-else>
        {{ t('characterSquare.commentWrite') }}
      </template>
    </button>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { deleteSquareComment, postSquareComment } from '@/api/characterSquare'

const props = defineProps({
  templateId: {
    type: Number,
    required: true,
  },
  comments: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['updated'])

const { t } = useI18n()

const editing = ref(false)
const draft = ref('')
const submitting = ref(false)
const deleting = ref(false)

const mineComment = computed(() => props.comments.find(item => item.isMine) || null)

watch(
  () => props.comments,
  () => {
    if (!editing.value && mineComment.value) {
      draft.value = mineComment.value.content
    }
  },
  { deep: true },
)

function openEdit() {
  draft.value = mineComment.value?.content || ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  draft.value = mineComment.value?.content || ''
}

async function submit() {
  const content = draft.value.trim()
  if (!content) {
    ElMessage.warning(t('characterSquare.commentEmpty'))
    return
  }
  submitting.value = true
  try {
    await postSquareComment(props.templateId, content)
    editing.value = false
    emit('updated')
    ElMessage.success(t('characterSquare.commentSaved'))
  } catch {
    /* interceptor */
  } finally {
    submitting.value = false
  }
}

async function remove() {
  deleting.value = true
  try {
    await deleteSquareComment(props.templateId)
    draft.value = ''
    editing.value = false
    emit('updated')
    ElMessage.success(t('characterSquare.commentDeleted'))
  } catch {
    /* interceptor */
  } finally {
    deleting.value = false
  }
}
</script>

<style lang="scss" scoped>
.square-comment-input {
  width: 100%;
  margin-top: $space-2;
  padding-top: $space-2;
  border-top: 1px dashed rgba($color-pink-rgb, 0.08);
}

.comment-trigger {
  width: 100%;
  border: none;
  background: transparent;
  color: $color-text-muted;
  font-size: $font-size-xs;
  text-align: left;
  padding: 2px 0;
  cursor: pointer;
  transition: color $transition-fast;

  &:hover {
    color: $color-pink-primary;
  }
}

.comment-field {
  width: 100%;
}

.comment-actions {
  display: flex;
  flex-wrap: wrap;
  gap: $space-1;
  justify-content: flex-end;
  margin-top: $space-2;
}
</style>
