<template>
  <div class="about-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('about.title') }}</h1>
      <p class="page-desc">{{ t('about.desc') }}</p>
    </header>

    <!-- 应用信息 -->
    <section class="section stagger-item">
      <div class="glass about-card">
        <div class="about-brand">
          <img :src="APP_LOGO" alt="LianYu" class="about-logo" @click="handleLogoClick" />
          <div class="about-brand__text">
            <div class="about-brand__name">恋语 <span class="about-brand__en">LianYu</span></div>
          </div>
        </div>
        <div class="about-info">
          <div class="info-row">
            <span class="info-label">{{ t('about.version') }}</span>
            <span class="info-value mono">v{{ version }}</span>
            <AppUpdateButton v-if="isElectron" />
          </div>
          <div class="info-row">
            <span class="info-label">{{ t('about.environment') }}</span>
            <span class="info-value">{{ isElectron ? t('about.envDesktop') : t('about.envWeb') }}</span>
          </div>
        </div>
        <p class="about-intro">{{ t('about.intro') }}</p>
      </div>
    </section>

    <!-- MCP 服务（桌面端） -->
    <section v-if="isElectron" class="section stagger-item">
      <div class="section-header">
        <h2 class="section-title">{{ t('about.mcpTitle') }}</h2>
      </div>
      <div class="glass about-card mcp-card">
        <div class="mcp-service">
          <div class="mcp-service__head">
            <div class="mcp-service__text">
              <div class="mcp-service__name">
                AgentAssistant
                <span class="mcp-badge" :class="`mcp-badge--${bridge.mcpState}`">{{ mcpStateLabel }}</span>
              </div>
              <p class="mcp-service__desc">{{ t('about.mcpAgentAssistantDesc') }}</p>
            </div>
            <el-switch
              :model-value="mcpEnabled"
              :loading="mcpToggling"
              @change="onToggleMcp"
            />
          </div>

          <div v-if="bridge.mcpError" class="mcp-service__error">{{ bridge.mcpError }}</div>

          <div v-if="mcpEnabled" class="mcp-service__body">
            <div class="info-row">
              <span class="info-label">{{ t('about.mcpBridge') }}</span>
              <span class="info-value">{{ bridge.registered ? t('about.mcpBridgeOnline') : t('about.mcpBridgeOffline') }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ t('about.mcpTools') }}</span>
              <span class="info-value">
                {{ bridge.tools.length ? bridge.tools.map(tool => tool.name).join('、') : '—' }}
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ t('about.mcpEngine') }}</span>
              <span class="info-value">{{ engineLabel }}</span>
            </div>
            <div v-if="engineBusy" class="mcp-engine-progress">
              <el-progress
                :percentage="enginePercent"
                :stroke-width="8"
              />
              <p class="mcp-engine-progress__meta">
                {{ enginePhaseLabel }}
                <span v-if="engineBytesLabel"> · {{ engineBytesLabel }}</span>
              </p>
            </div>
            <div class="mcp-advanced__row">
              <span class="mcp-advanced__label">{{ t('about.mcpAutoApprove') }}</span>
              <el-switch :model-value="autoApprove" @change="onToggleAutoApprove" />
            </div>
            <p class="mcp-advanced__hint">{{ t('about.mcpAutoApproveHint') }}</p>
            <el-collapse v-model="advancedOpen" class="mcp-advanced">
              <el-collapse-item :title="t('about.mcpAdvanced')" name="advanced">
                <div class="mcp-advanced__row">
                  <span class="mcp-advanced__label">{{ t('about.mcpUseDemo') }}</span>
                  <el-switch :model-value="usingDemo" @change="onToggleDemo" />
                </div>
                <template v-if="!usingDemo">
                  <div class="mcp-advanced__row mcp-advanced__row--column">
                    <span class="mcp-advanced__label">{{ t('about.mcpCommand') }}</span>
                    <el-input
                      v-model="commandDraft"
                      :placeholder="t('about.mcpCommandPlaceholder')"
                      @blur="onCommandBlur"
                    />
                  </div>
                  <div class="mcp-advanced__row mcp-advanced__row--column">
                    <span class="mcp-advanced__label">{{ t('about.mcpArgs') }}</span>
                    <el-input
                      v-model="argsDraft"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 6 }"
                      :placeholder="t('about.mcpArgsPlaceholder')"
                      @blur="onArgsBlur"
                    />
                    <p class="mcp-advanced__hint">{{ t('about.mcpArgsHint') }}</p>
                  </div>
                  <div class="mcp-advanced__row mcp-advanced__row--column">
                    <span class="mcp-advanced__label">{{ t('about.mcpCwd') }}</span>
                    <el-input
                      v-model="cwdDraft"
                      :placeholder="t('about.mcpCwdPlaceholder')"
                      @blur="onCwdBlur"
                    />
                    <p class="mcp-advanced__hint">{{ t('about.mcpCommandHint') }}</p>
                  </div>
                </template>
              </el-collapse-item>
            </el-collapse>
          </div>
          <p v-else class="mcp-service__hint">{{ t('about.mcpDisabledHint') }}</p>
          <div v-if="!mcpEnabled && engineBusy" class="mcp-engine-progress mcp-engine-progress--disabled">
            <el-progress
              :percentage="enginePercent"
              :stroke-width="8"
            />
            <p class="mcp-engine-progress__meta">
              {{ enginePhaseLabel }}
              <span v-if="engineBytesLabel"> · {{ engineBytesLabel }}</span>
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- 版权 -->
    <section class="section stagger-item">
      <div class="glass about-card about-footer">
        <span class="about-copyright">{{ t('about.copyright') }}</span>
      </div>
    </section>

    <!-- 开发者 -->
    <section class="section stagger-item">
      <div class="section-header">
        <h2 class="section-title">开发者</h2>
      </div>
      <div class="glass about-card about-devs">
        <div class="dev-group">
          <h3 class="dev-role">核心主开发者</h3>
          <a :href="'https://github.com/whitequeen306'" target="_blank" rel="noopener noreferrer" class="dev-item">
            <img src="/devs/white-queen.jpg" alt="白之女王" class="dev-avatar" />
            <span class="dev-name">白之女王</span>
            <el-icon class="dev-link-icon"><Link /></el-icon>
          </a>
        </div>
        <div class="dev-group">
          <h3 class="dev-role">开发者及 API 支持</h3>
          <a :href="'https://github.com/2164312714-svg'" target="_blank" rel="noopener noreferrer" class="dev-item">
            <img src="/devs/clove.jpg" alt="Clove" class="dev-avatar" />
            <span class="dev-name">Clove.</span>
            <el-icon class="dev-link-icon"><Link /></el-icon>
          </a>
        </div>
        <div class="dev-group">
          <h3 class="dev-role">其它鸣谢</h3>
          <p class="dev-item dev-thanks">恋语安卓端全体开发团队以及各位用户</p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { isElectronApp } from '@/utils/electron'
import { useAgentBridgeStore } from '@/stores/agentBridge'
import AppUpdateButton from '@/components/AppUpdateButton.vue'
import { ArrowLeft, Link } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { APP_LOGO } from '@/constants/brand'
import pkg from '../../package.json'

const { t } = useI18n()
const router = useRouter()
const isElectron = isElectronApp()
// 版本号取自 package.json，构建时注入；桌面版亦可由主进程覆盖但此处统一用前端版本
const version = computed(() => pkg.version || '—')
const goBack = () => router.push('/app/settings')

// ---- MCP 服务（Agent 工具桥） ----
const bridge = useAgentBridgeStore()
const mcpToggling = ref(false)
const advancedOpen = ref([])
const commandDraft = ref('')
const argsDraft = ref('')
const cwdDraft = ref('')

const mcpSettings = computed(() => bridge.settings)
const mcpEnabled = computed(() => mcpSettings.value?.enabled === true)
const autoApprove = computed(() => mcpSettings.value?.autoApprove === true)
const usingDemo = computed(() => mcpSettings.value?.useDemoServer === true)
const usingCustom = computed(() => !usingDemo.value && Boolean(mcpSettings.value?.command))

const engineLabel = computed(() => {
  if (usingDemo.value) return t('about.mcpEngineDemo')
  if (usingCustom.value) return mcpSettings.value.command
  if (bridge.engineStatus?.installed && bridge.engineStatus.version) {
    return t('about.mcpEngineManaged', { version: bridge.engineStatus.version })
  }
  return t('about.mcpEngineNotInstalled')
})

const engineBusy = computed(() => {
  const phase = bridge.engineProgress?.phase
  return phase === 'downloading' || phase === 'extracting'
})
const enginePercent = computed(() => {
  const n = Number(bridge.engineProgress?.percent)
  return Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0
})
const enginePhaseLabel = computed(() => {
  const phase = bridge.engineProgress?.phase
  if (phase === 'extracting') return t('about.mcpEngineExtracting')
  if (phase === 'downloading') return t('about.mcpEngineDownloading')
  return ''
})
const engineBytesLabel = computed(() => {
  const p = bridge.engineProgress
  if (!p?.total) return ''
  return `${formatBytes(p.received || 0)} / ${formatBytes(p.total)}`
})

function formatBytes(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / (1024 * 1024)).toFixed(1)} MB`
}

const mcpStateLabel = computed(() => {
  switch (bridge.mcpState) {
    case 'running': return t('about.mcpStateRunning')
    case 'starting': return t('about.mcpStateStarting')
    case 'error': return t('about.mcpStateError')
    default: return t('about.mcpStateStopped')
  }
})

watch(mcpSettings, (s) => {
  commandDraft.value = s?.command || ''
  argsDraft.value = Array.isArray(s?.args) ? s.args.join('\n') : ''
  cwdDraft.value = s?.cwd || ''
}, { immediate: true })

function parseArgsInput(text) {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

async function onToggleMcp(enabled) {
  mcpToggling.value = true
  try {
    if (enabled === true && !usingDemo.value && !usingCustom.value) {
      const res = await bridge.installEngine()
      if (!res?.ok) {
        ElMessage.error(res?.error || t('about.mcpEngineDownloadFailed'))
        return
      }
    }
    await bridge.updateSettings({ enabled: enabled === true })
  } finally {
    mcpToggling.value = false
  }
}

async function onToggleAutoApprove(value) {
  await bridge.updateSettings({ autoApprove: value === true })
}

async function onToggleDemo(useDemo) {
  if (useDemo !== true && !mcpSettings.value?.command) {
    const res = await bridge.installEngine()
    if (!res?.ok) {
      ElMessage.error(res?.error || t('about.mcpEngineDownloadFailed'))
      return
    }
  }
  await bridge.updateSettings({ useDemoServer: useDemo === true })
}

async function onCommandBlur() {
  const command = commandDraft.value.trim()
  if (command === (mcpSettings.value?.command || '')) return
  await bridge.updateSettings({ command })
}

async function onArgsBlur() {
  const args = parseArgsInput(argsDraft.value)
  const current = Array.isArray(mcpSettings.value?.args) ? mcpSettings.value.args : []
  if (JSON.stringify(args) === JSON.stringify(current)) return
  await bridge.updateSettings({ args })
}

async function onCwdBlur() {
  const cwd = cwdDraft.value.trim()
  if (cwd === (mcpSettings.value?.cwd || '')) return
  await bridge.updateSettings({ cwd })
}

onMounted(() => {
  if (isElectron) {
    void bridge.init()
    void bridge.refreshSettings()
    void bridge.refreshEngineStatus()
  }
})

// 彩蛋：连续点击恋语图标 10 次跳转爱发电赞助页。
// 计数窗口 2s，中断则重置，避免误触。
const SPONSOR_URL = 'https://ifdian.net/a/Lianyuchat'
const logoClickCount = ref(0)
let logoClickTimer = null
function handleLogoClick() {
  logoClickCount.value += 1
  if (logoClickTimer) clearTimeout(logoClickTimer)
  logoClickTimer = setTimeout(() => { logoClickCount.value = 0 }, 2000)
  if (logoClickCount.value >= 10) {
    logoClickCount.value = 0
    if (logoClickTimer) { clearTimeout(logoClickTimer); logoClickTimer = null }
    // Electron 下经 setWindowOpenHandler 走 shell.openExternal（需 host 在白名单）；
    // Web 下浏览器原生 window.open 直接生效。
    window.open(SPONSOR_URL, '_blank', 'noopener,noreferrer')
  }
}
</script>

<style lang="scss" scoped>
.about-page {
  max-width: $narrow-page-max;
}

.section {
  & + .section { margin-top: $space-12; }
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: $space-6;
}

.section-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.about-card {
  border-radius: $radius-lg;
  padding: $space-6;
}

.about-brand {
  display: flex;
  align-items: center;
  gap: $space-4;
  margin-bottom: $space-5;
  padding-bottom: $space-5;
  border-bottom: 1px solid rgba(128, 128, 140, 0.15);
}

.about-logo {
  width: 56px;
  height: 56px;
  border-radius: $radius-lg;
  object-fit: contain;
  flex-shrink: 0;
  cursor: pointer;
  user-select: none;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  &:hover { transform: scale(1.06); }
  &:active { transform: scale(0.96); }
}

.about-brand__name {
  font-size: $font-size-xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
}

.about-brand__en {
  font-size: $font-size-sm;
  color: $color-text-muted;
  margin-left: $space-2;
  font-weight: $font-weight-normal;
}

.about-info {
  display: flex;
  flex-direction: column;
  gap: $space-3;
  margin-bottom: $space-5;
  padding-bottom: $space-5;
  border-bottom: 1px solid rgba(128, 128, 140, 0.15);
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: $space-3;
}

.info-label {
  font-size: $font-size-xs;
  color: $color-text-muted;
  min-width: 72px;
  flex-shrink: 0;
}

.info-value {
  font-size: $font-size-sm;
  color: $color-text-secondary;

  &.mono {
    font-family: $font-mono;
    font-size: $font-size-xs;
    opacity: 0.75;
  }
}

.about-intro {
  font-size: $font-size-sm;
  color: $color-text-secondary;
  line-height: $line-height-normal;
  margin: 0;
}

.about-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-4;
}

.mcp-service__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $space-4;
}

.mcp-service__name {
  display: flex;
  align-items: center;
  gap: $space-2;
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
}

.mcp-badge {
  padding: 1px $space-2;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-normal;
  color: $color-text-secondary;
  background: rgba(128, 128, 140, 0.12);

  &--running {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.12);
  }

  &--starting {
    color: $color-text-secondary;
    background: rgba($color-pink-rgb, 0.08);
  }

  &--error {
    color: $color-error;
    background: rgba(128, 128, 140, 0.1);
  }
}

.mcp-service__desc {
  margin: $space-1 0 0;
  font-size: $font-size-sm;
  color: $color-text-secondary;
  line-height: $line-height-normal;
}

.mcp-service__error {
  margin-top: $space-3;
  font-size: $font-size-xs;
  color: $color-error;
}

.mcp-service__body {
  display: flex;
  flex-direction: column;
  gap: $space-3;
  margin-top: $space-5;
  padding-top: $space-5;
  border-top: 1px solid rgba(128, 128, 140, 0.15);
}

.mcp-service__hint {
  margin: $space-4 0 0;
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.mcp-engine-progress {
  display: flex;
  flex-direction: column;
  gap: $space-2;

  &--disabled {
    margin-top: $space-4;
  }
}

.mcp-engine-progress__meta {
  margin: 0;
  font-size: $font-size-xs;
  color: $color-text-muted;
  font-family: $font-mono;
}

.mcp-advanced {
  border: none;

  :deep(.el-collapse-item__header) {
    background: transparent;
    border: none;
    font-size: $font-size-xs;
    color: $color-text-muted;
    height: 32px;
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
    border: none;
  }
}

.mcp-advanced__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-3;
  padding: $space-2 0;

  &--column {
    flex-direction: column;
    align-items: stretch;
  }
}

.mcp-advanced__label {
  font-size: $font-size-sm;
  color: $color-text-secondary;
}

.mcp-advanced__hint {
  margin: $space-1 0 0;
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.about-copyright {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.about-devs {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.dev-item {
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: rgba(128, 128, 140, 0.06);
  text-decoration: none;
  color: $color-text-primary;
  font-size: $font-size-base;
  font-weight: $font-weight-medium;
  transition: all $transition-fast;
  &:hover {
    background: rgba($color-pink-rgb, 0.08);
    color: $color-pink-primary;
  }
}

.dev-avatar {
  width: 40px;
  height: 40px;
  border-radius: $radius-full;
  object-fit: cover;
  flex-shrink: 0;
}

.dev-link-icon {
  margin-left: auto;
  font-size: $font-size-sm;
  color: $color-text-muted;
  .dev-item:hover & { color: $color-pink-primary; }
}

.dev-group {
  & + .dev-group {
    margin-top: $space-4;
  }
}

.dev-role {
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  color: $color-text-muted;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 $space-2 0;
}

.dev-thanks {
  cursor: default;
  &:hover {
    background: rgba(128, 128, 140, 0.06);
    color: $color-text-primary;
  }
}
</style>
