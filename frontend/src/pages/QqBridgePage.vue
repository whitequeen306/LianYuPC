<template>
  <div class="qq-bridge-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('routes.qqBridge') }}</h1>
      <p class="page-desc">{{ t('qqBridge.desc') }}</p>
    </header>

    <section v-if="!isElectron" class="section stagger-item">
      <div class="glass qq-card">
        <p class="hint">{{ t('qqBridge.notElectron') }}</p>
      </div>
    </section>

    <template v-else>
      <!-- 托管模式 -->
      <section class="section stagger-item">
        <div class="section-header">
          <div>
            <h2 class="section-title">{{ t('qqBridge.mode.title') }}</h2>
            <p class="section-desc">{{ t('qqBridge.mode.hint') }}</p>
          </div>
          <el-switch
            v-model="autoModeRef"
            :active-text="t('qqBridge.mode.auto')"
            :inactive-text="t('qqBridge.mode.manual')"
            inline-prompt
            @change="onToggleAuto"
          />
        </div>
      </section>

      <!-- 运行状态（合并 NapCat 运行时 + 桥接状态为一处 hero 面板） -->
      <section class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('qqBridge.status.title') }}</h2></div>
          <div class="host-actions">
            <!-- 自动模式：托管按钮组 -->
            <template v-if="autoModeRef">
              <!-- busy（下载/启动中）：取消，中断进行中的下载/启动 -->
              <el-button
                v-if="hostPhase === 'busy'"
                type="warning"
                plain
                :loading="actionInFlight"
                @click="onStopHost"
              >{{ t('qqBridge.host.cancel') }}</el-button>
              <!-- running：停止托管 -->
              <el-button
                v-else-if="hostRunning"
                type="danger"
                plain
                :loading="actionInFlight"
                @click="onStopHost"
              >{{ t('qqBridge.host.stop') }}</el-button>
              <!-- idle/error：启动（或失败后重试） -->
              <el-button
                v-else
                type="primary"
                :loading="actionInFlight"
                @click="onStartHost"
              >{{ t('qqBridge.host.start') }}</el-button>
              <el-button
                type="primary"
                :disabled="!hostRunning"
                @click="onLogin"
              >{{ t('qqBridge.host.login') }}</el-button>
              <!-- 重装/修复常驻：已安装且非 busy 即可点；有新版显示「升级」语义 -->
              <el-button
                v-if="canReinstall"
                type="warning"
                plain
                :loading="reinstalling"
                @click="onReinstall"
              >{{ upgradeAvailable ? t('qqBridge.host.reinstall') : t('qqBridge.host.repair') }}</el-button>
            </template>
            <!-- 手动模式：桥接启停 -->
            <template v-else>
              <el-button
                v-if="!bridgeActive"
                type="primary"
                @click="onStartBridge"
              >{{ t('qqBridge.bridge.start') }}</el-button>
              <el-button v-else type="warning" plain @click="onStopBridge">
                {{ t('qqBridge.bridge.stop') }}
              </el-button>
            </template>
          </div>
        </div>
        <div class="glass qq-card status-card">
          <!-- 自动模式：NapCat 运行时状态行（版本/令牌/升级提示） -->
          <div v-if="autoModeRef" class="status-row">
            <span class="status-dot" :class="stateClass(hostStatus.state)"></span>
            <span class="status-label">{{ stateLabel(hostStatus.state) }}</span>
            <span v-if="hostStatus.version" class="status-meta">
              {{ t('qqBridge.host.version') }}: {{ hostStatus.version }}
            </span>
            <span v-if="hostStatus.webui?.token || settings.hosting?.webuiToken" class="status-meta">
              {{ t('qqBridge.host.token') }}: {{ hostStatus.webui?.token || settings.hosting?.webuiToken }}
            </span>
            <span v-if="upgradeAvailable" class="status-meta upgrade-hint">
              {{ t('qqBridge.host.upgradeHint', { latest: hostStatus.upgrade.latest, installed: hostStatus.upgrade.installed }) }}
            </span>
          </div>
          <!-- 桥接状态行（自动/手动均显示；selfId 归属此处） -->
          <div class="status-row">
            <span class="status-dot" :class="stateClass(bridgeStatus.state)"></span>
            <span class="status-label">{{ stateLabel(bridgeStatus.state) }}</span>
            <span v-if="bridgeStatus.selfId" class="status-meta">
              {{ t('qqBridge.bridge.selfId') }}: {{ bridgeStatus.selfId }}
            </span>
            <span v-else-if="autoModeRef && hostRunning" class="status-meta muted">
              {{ t('qqBridge.host.noSelfId') }}
            </span>
          </div>
        </div>
      </section>

      <!-- 消息路由 -->
      <section class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('qqBridge.binding.title') }}</h2></div>
        </div>
        <div class="glass qq-card binding-form">
          <el-form label-position="top">
            <el-form-item :label="t('qqBridge.binding.character')">
              <el-select
                v-model="bindingForm.characterId"
                :placeholder="t('qqBridge.binding.characterPlaceholder')"
                :loading="characterLoading"
                filterable
                style="width: 100%"
                @visible-change="onCharDropdownVisible"
              >
                <el-option
                  v-for="opt in characterOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
                <template #empty>
                  <div class="conv-empty">
                    <span v-if="characterLoading">{{ t('common.loading') }}</span>
                    <span v-else>{{ t('qqBridge.binding.characterEmpty') }}</span>
                  </div>
                </template>
              </el-select>
              <p class="field-hint">{{ t('qqBridge.binding.characterHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('qqBridge.binding.allowMode')">
              <el-select v-model="bindingForm.allowMode" style="width: 100%">
                <el-option :label="t('qqBridge.binding.allowModeAllowlist')" value="allowlist" />
                <el-option :label="t('qqBridge.binding.allowModeOpen')" value="open" />
              </el-select>
            </el-form-item>
            <template v-if="bindingForm.allowMode === 'open'">
              <el-alert type="warning" :closable="false" show-icon :title="t('qqBridge.binding.openModeWarning')" />
            </template>
            <template v-else>
              <div class="allowlist-grid">
                <el-form-item :label="t('qqBridge.binding.allowUsers')">
                  <el-input v-model="bindingForm.allowUsers" :placeholder="t('qqBridge.binding.allowHint')" />
                </el-form-item>
                <el-form-item :label="t('qqBridge.binding.allowGroups')">
                  <el-input v-model="bindingForm.allowGroups" :placeholder="t('qqBridge.binding.allowHint')" />
                </el-form-item>
              </div>
              <el-alert
                v-if="!bindingForm.allowUsers.trim() && !bindingForm.allowGroups.trim()"
                type="info"
                :closable="false"
                show-icon
                :title="t('qqBridge.binding.emptyAllowlistWarning')"
              />
            </template>
          </el-form>
          <div class="form-footer">
            <el-button type="primary" :loading="savingBinding" @click="onSaveBinding">
              {{ t('qqBridge.binding.save') }}
            </el-button>
          </div>
        </div>
      </section>

      <!-- 回复设置（分段延迟 + 兜底回复） -->
      <section class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('qqBridge.reply.title') }}</h2></div>
        </div>
        <div class="glass qq-card reply-form">
          <el-form label-position="top">
            <el-form-item :label="t('qqBridge.reply.segmentDelay')">
              <div class="delay-row">
                <el-input-number
                  v-model="replyForm.segmentDelayMs"
                  :min="0"
                  :max="2000"
                  :step="100"
                  controls-position="right"
                  class="delay-input"
                />
                <span class="delay-unit">ms</span>
              </div>
              <p class="field-hint">{{ t('qqBridge.reply.segmentDelayHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('qqBridge.reply.segmentJitter')">
              <div class="delay-row">
                <el-input-number
                  v-model="replyForm.segmentJitterMs"
                  :min="0"
                  :max="3000"
                  :step="100"
                  controls-position="right"
                  class="delay-input"
                />
                <span class="delay-unit">ms</span>
              </div>
              <p class="field-hint">{{ t('qqBridge.reply.segmentJitterHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('qqBridge.reply.fallback')">
              <el-input
                v-model="replyForm.fallbackText"
                type="textarea"
                :rows="2"
                :placeholder="t('qqBridge.reply.fallbackHint')"
              />
              <p class="field-hint">{{ t('qqBridge.reply.fallbackHint') }}</p>
            </el-form-item>
          </el-form>
          <div class="form-footer">
            <p class="field-hint restart-hint">{{ t('qqBridge.reply.applyHint') }}</p>
            <el-button type="primary" :loading="savingReply" @click="onSaveReply">
              {{ t('qqBridge.reply.save') }}
            </el-button>
          </div>
        </div>
      </section>

      <!-- NapCat 连接（手动模式） -->
      <section v-if="!autoModeRef" class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('qqBridge.ws.title') }}</h2></div>
        </div>
        <div class="glass qq-card binding-form">
          <p class="field-hint">{{ t('qqBridge.ws.hint') }}</p>
          <el-form label-position="top">
            <el-form-item :label="t('qqBridge.ws.wsUrl')">
              <el-input v-model="wsForm.wsUrl" placeholder="ws://127.0.0.1:3001" />
            </el-form-item>
            <el-form-item :label="t('qqBridge.ws.accessToken')">
              <el-input v-model="wsForm.accessToken" placeholder="（无令牌则留空）" />
            </el-form-item>
          </el-form>
          <div class="form-footer">
            <el-button type="primary" :loading="savingWs" @click="onSaveWs">
              {{ t('qqBridge.ws.save') }}
            </el-button>
          </div>
        </div>
      </section>

      <!-- 桥接日志（页内结构化卡片，替代原弹窗） -->
      <section class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('qqBridge.logs.title') }}</h2></div>
          <el-button size="small" :loading="logsLoading" @click="refreshLogs">{{ t('qqBridge.logs.refresh') }}</el-button>
        </div>
        <div class="glass qq-card logs-card">
          <div class="logs-toolbar">
            <el-radio-group v-model="logFilter" size="small">
              <el-radio-button value="all">{{ t('qqBridge.logs.all') }}</el-radio-button>
              <el-radio-button value="success">{{ t('qqBridge.logs.success') }}</el-radio-button>
              <el-radio-button value="fail">{{ t('qqBridge.logs.fail') }}</el-radio-button>
            </el-radio-group>
            <div class="logs-toolbar-right">
              <el-checkbox v-model="autoRefresh" size="small">{{ t('qqBridge.logs.autoRefresh') }}</el-checkbox>
              <span class="logs-count">{{ t('qqBridge.logs.count', { n: filteredLogs.length }) }}</span>
            </div>
          </div>
          <div v-if="!filteredLogs.length" class="logs-empty">{{ t('qqBridge.logs.empty') }}</div>
          <div v-else ref="logListRef" class="logs-list">
            <div
              v-for="(l, i) in filteredLogs"
              :key="i"
              class="log-line"
              :class="'is-' + l.level"
            >
              <span class="log-time">{{ l.time }}</span>
              <el-tag v-if="l.tag" size="small" effect="plain" class="log-tag">{{ l.tag }}</el-tag>
              <span class="log-msg">{{ l.msg }}</span>
            </div>
          </div>
          <p class="field-hint">{{ t('qqBridge.logs.autoRecoverHint') }}</p>
        </div>
      </section>

      <!-- 下载进度弹窗：单独界面（非页内进度条），下载进行中模态屏蔽其余选项
           （不可点关/ESC/遮罩关闭），由 store 在下载完成/失败/进入运行态清空
           downloadProgress 后自动消失——满足「单独弹界面显示进度」+「未下载完不准选」。 -->
      <el-dialog
        v-model="downloadDialogVisible"
        :title="t('qqBridge.download.title')"
        :show-close="false"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        :modal="true"
        width="460px"
        align-center
      >
        <div class="download-dialog-body">
          <div class="download-phase">
            <span class="status-dot" :class="downloadProgress?.phase === 'done' ? 'is-ok' : 'is-busy'"></span>
            <span>{{ phaseLabel(downloadProgress?.phase) }}</span>
          </div>
          <el-progress
            :percentage="downloadProgress?.percent || 0"
            :status="downloadProgress?.phase === 'done' ? 'success' : undefined"
            :stroke-width="14"
          />
          <div v-if="downloadProgress?.total" class="download-bytes">
            {{ formatBytes(downloadProgress?.received || 0) }} / {{ formatBytes(downloadProgress?.total || 0) }}
            <span v-if="downloadProgress?.phase === 'downloading' && downloadProgress?.speed" class="download-speed">
              · {{ formatSpeed(downloadProgress.speed) }}
            </span>
          </div>
        </div>
        <template #footer>
          <div class="download-footer">
            <span class="download-hint">{{ t('qqBridge.download.hint') }}</span>
            <el-button
              v-if="downloadProgress?.phase !== 'done'"
              size="small"
              :loading="actionInFlight"
              @click="onStopHost"
            >{{ t('qqBridge.download.cancel') }}</el-button>
          </div>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQqBridgeStore } from '@/stores/qqBridge'
import { isElectronApp } from '@/utils/electron'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { listCharacters } from '@/api/character'

const { t } = useI18n()
const router = useRouter()
const store = useQqBridgeStore()
const isElectron = isElectronApp()
const goBack = () => router.push('/app/settings')

const hostStatus = computed(() => store.hostStatus || { state: 'stopped' })
const bridgeStatus = computed(() => store.bridgeStatus || { state: 'stopped' })
const downloadProgress = computed(() => store.downloadProgress)
// 下载进度弹窗可见性：由 store 的 downloadProgress 派生——有进度即弹，无即隐。
// setter 空实现：模态不可由用户关闭（show-close/ESC/遮罩均禁用），仅由 store 在
// 下载完成/失败/进入运行态清空 downloadProgress 后自动消失（满足「未下载完不准选」）。
const downloadDialogVisible = computed({
  get: () => !!downloadProgress.value,
  set: () => {},
})
const settings = computed(() => store.settings || {})

// 主机状态归类：把 napcatHost 的细粒度状态压成 4 个 UI 相位，驱动按钮显隐与文案。
// idle=未运行；busy=下载/配置/启动/重启等过渡态；running=已就绪可用；error=失败/断开。
// 相位化避免「下载中却显示启动按钮可重复点」「restarting 显示英文原值」等失步。
const HOST_BUSY_STATES = new Set(['resolving-release', 'downloading', 'extracting', 'writing-config', 'launching', 'starting', 'restarting', 'reconnecting', 'connecting'])
const HOST_RUNNING_STATES = new Set(['running', 'ready', 'connected'])
const hostPhase = computed(() => {
  const s = hostStatus.value.state
  if (HOST_RUNNING_STATES.has(s)) return 'running'
  if (HOST_BUSY_STATES.has(s)) return 'busy'
  if (s === 'error' || s === 'disconnected') return 'error'
  return 'idle'
})
const hostRunning = computed(() => hostPhase.value === 'running')
// 操作进行中（点击到主进程推送到达的窗口期）与过渡态取或，确保按钮全程 loading
const actionInFlight = ref(false)
const hostBusy = computed(() => hostPhase.value === 'busy' || actionInFlight.value)
// 重装/修复常驻：已安装（曾记录 version）且非 busy 即可点；有新版时按钮文案切到「升级」
const canReinstall = computed(() => hostPhase.value !== 'busy' && !!hostStatus.value.version)
const upgradeAvailable = computed(() => !!hostStatus.value.upgrade)
const bridgeActive = computed(() => {
  const s = bridgeStatus.value.state
  return s !== 'stopped' && s !== 'disconnected' && s !== 'error'
})

const reinstalling = ref(false)
const savingBinding = ref(false)
const savingWs = ref(false)
const savingReply = ref(false)

const autoModeRef = ref(false)
const bindingForm = reactive({ characterId: '', allowMode: 'allowlist', allowUsers: '', allowGroups: '' })
const wsForm = reactive({ wsUrl: 'ws://127.0.0.1:3001', accessToken: '' })
// 回复设置表单：segmentDelayMs（分段逐条发送条间延迟）+ fallbackText（兜底回复）。
// 保存时须展开当前 settings.reply 以保留 timeoutMs 等未在 UI 暴露的字段
// （writeQqBridgeSettings 顶层浅合并，reply 整体替换；缺字段会被 normalize 回落默认值）。
const replyForm = reactive({ segmentDelayMs: 500, segmentJitterMs: 800, fallbackText: '' })

// 消息路由下拉：拉取云端角色列表给「角色名」可读选项——选定角色后桥接按角色自动匹配会话，
// 每个 QQ 用户独立会话、不受清空上下文影响，无需再粘会话号/开额外弹窗。
const characterOptions = ref([])
const characterLoading = ref(false)

// 设置变更后同步本地表单（首次与主进程同步后触发）
watch(
  settings,
  (s) => {
    if (!s) return
    autoModeRef.value = s.hosting?.mode === 'auto'
    bindingForm.characterId = s.binding?.characterId || ''
    bindingForm.allowMode = s.binding?.allowMode === 'open' ? 'open' : 'allowlist'
    bindingForm.allowUsers = joinList(s.binding?.allowUsers)
    bindingForm.allowGroups = joinList(s.binding?.allowGroups)
    if (s.napcat?.wsUrl) wsForm.wsUrl = s.napcat.wsUrl
    if (s.napcat?.accessToken != null) wsForm.accessToken = s.napcat.accessToken
    // 允许 0（不延迟）：显式取非负有限数，否则回落 500（|| 会把 0 当假值）
    const segDelayMs = Number(s.reply?.segmentDelayMs)
    replyForm.segmentDelayMs = Number.isFinite(segDelayMs) && segDelayMs >= 0 ? segDelayMs : 500
    const segJitterMs = Number(s.reply?.segmentJitterMs)
    replyForm.segmentJitterMs = Number.isFinite(segJitterMs) && segJitterMs >= 0 ? segJitterMs : 800
    replyForm.fallbackText = s.reply?.fallbackText ?? ''
  },
  { immediate: true },
)

// 绑定角色 id 变了且不在已加载选项里（如主进程同步后首次出现）→ 重新拉取，
// 让下拉显示可读标签而不是裸 id；id 已在选项里则不重复拉（避免选中时抖动）
watch(
  () => bindingForm.characterId,
  (id) => {
    if (id && !characterOptions.value.find((o) => o.value === String(id))) {
      loadCharacterOptions()
    }
  },
)

function stateLabel(s) {
  const key = 'qqBridge.state.' + (s || 'stopped')
  const v = t(key)
  return v === key ? (s || 'stopped') : v
}

// 下载相位本地化：preparing/downloading/extracting/done 映射为文案；未配置相位回落原值
function phaseLabel(p) {
  const key = 'qqBridge.download.phase.' + (p || '')
  const v = t(key)
  return v === key ? (p || '') : v
}

// 失败原因本地化：主进程返回 {ok:false,reason}，按 reason 给针对性文案；
// 未配置则回落到调用方传入的 fallbackKey（各动作的通用失败提示）
function reasonText(reason, fallbackKey = 'qqBridge.host.startFailed') {
  const key = 'qqBridge.reason.' + (reason || 'unknown')
  const v = t(key)
  return v === key ? t(fallbackKey) : v
}

// 字节数友好化：29MB 的 Shell 显示为 "29.0 MB"，配合进度弹窗展示已下/总大小
function formatBytes(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

// 下载速率友好化：bytes/sec → KB/s 或 MB/s，仅 downloading 相位显示实时网速
function formatSpeed(n) {
  const v = Number(n) || 0
  if (v < 1) return '0 KB/s'
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB/s`
  return `${(v / 1024 / 1024).toFixed(2)} MB/s`
}

function stateClass(s) {
  if (s === 'running' || s === 'ready' || s === 'connected') return 'is-ok'
  if (s === 'error' || s === 'disconnected') return 'is-err'
  if (s === 'stopped') return 'is-off'
  return 'is-busy'
}

function splitList(str) {
  return String(str || '')
    .split(/[,，\s]+/)
    .map((v) => v.trim())
    .filter(Boolean)
}

function joinList(arr) {
  return Array.isArray(arr) ? arr.join(', ') : ''
}

// 拉取云端角色列表构造可读下拉选项。绑定的角色 id 若不在列表（已删/未加载），
// 注入一条「当前绑定」占位项，保证下拉里始终看得到当前值，不会显示裸 id。
async function loadCharacterOptions() {
  characterLoading.value = true
  try {
    const chars = await listCharacters({ silent: true })
    const arr = Array.isArray(chars) ? chars : []
    const opts = arr.map((c) => ({ value: String(c.id), label: c.name || `#${c.id}` }))
    const boundId = bindingForm.characterId
    if (boundId && !opts.find((o) => o.value === String(boundId))) {
      opts.unshift({
        value: String(boundId),
        label: t('qqBridge.binding.currentlyBound') + ' #' + boundId,
      })
    }
    characterOptions.value = opts
  } catch (e) {
    // 拉取失败不阻塞：保存仍可写回原值；下拉空时由 #empty 提示去 App 建角色
    console.warn('[QqBridge] loadCharacterOptions failed:', e?.message || e)
  } finally {
    characterLoading.value = false
  }
}

// 下拉展开时刷新（用户可能刚在 App 里建了新角色）
function onCharDropdownVisible(isOpen) {
  if (isOpen && !characterLoading.value) loadCharacterOptions()
}

async function onToggleAuto(v) {
  if (v) {
    try {
      await ElMessageBox.confirm(t('qqBridge.consent.body'), t('qqBridge.consent.title'), {
        confirmButtonText: t('qqBridge.consent.confirm'),
        cancelButtonText: t('qqBridge.consent.cancel'),
        type: 'warning',
      })
    } catch {
      autoModeRef.value = false
      return
    }
    await store.setSettings({ hosting: { mode: 'auto', consented: true } })
    const r = await store.startHost()
    if (r && r.ok === false) {
      // 启动失败：回退到 manual，避免 mode=auto 但 host 未起的失步状态；
      // 原因由 reasonText 给出（not_consented/start_failed/exception 等）
      ElMessage.warning(reasonText(r.reason))
      autoModeRef.value = false
      try { await store.setSettings({ hosting: { mode: 'manual' } }) } catch { /* ignore */ }
      return
    }
  } else {
    // 降级为手动：先停托管，再持久化 manual。停托管失败不阻塞降级——
    // 仍须写回 manual，否则下次启动仍会自动拉起（与用户意图相悖）。
    // 停托管失败时 UI 状态由兜底轮询（store 5s 对账）纠正，此处仅记日志。
    try {
      await store.stopHost()
    } catch (e) {
      console.warn('[QqBridge] downgrade to manual: stopHost failed:', e?.message || e)
    } finally {
      await store.setSettings({ hosting: { mode: 'manual' } })
    }
  }
}

async function onStartHost() {
  actionInFlight.value = true
  try {
    const r = await store.startHost()
    // 启动失败（未授权/非自动模式/下载或启动出错）：按 reason 提示，避免「点击无响应」
    if (r && r.ok === false) ElMessage.warning(reasonText(r.reason))
  } finally {
    actionInFlight.value = false
  }
}

async function onStopHost() {
  actionInFlight.value = true
  try {
    const r = await store.stopHost()
    if (r && r.ok === false) ElMessage.warning(t('qqBridge.host.stopFailed'))
  } finally {
    actionInFlight.value = false
  }
}

async function onLogin() {
  const r = await store.openLoginWindow()
  if (r && r.ok === false) ElMessage.warning(reasonText(r.reason, 'qqBridge.host.loginFailed'))
}

async function onReinstall() {
  try {
    await ElMessageBox.confirm(t('qqBridge.host.reinstallConfirm'), t('qqBridge.host.reinstall'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
  } catch {
    return
  }
  reinstalling.value = true
  try {
    const r = await store.reinstallHost()
    if (r && r.ok === false) ElMessage.warning(reasonText(r.reason, 'qqBridge.host.reinstallFailed'))
    else ElMessage.success(t('qqBridge.host.reinstallOk'))
  } finally {
    reinstalling.value = false
  }
}

async function onStartBridge() {
  try {
    const r = await store.startBridge()
    // 失败时按主进程返回的 reason 给针对性提示（未选会话/未登录/未保存连接等），
    // 命中 reason 映射则用映射文案，否则回退通用 startFailed——避免「只说失败不说原因」。
    if (r && r.ok === false) ElMessage.warning(reasonText(r.reason, 'qqBridge.bridge.startFailed'))
    else if (r && r.ok === true) ElMessage.success(t('qqBridge.bridge.startOk'))
  } catch {
    ElMessage.warning(reasonText('exception', 'qqBridge.bridge.startFailed'))
  }
}

async function onStopBridge() {
  try {
    const r = await store.stopBridge()
    if (r && r.ok === false) ElMessage.warning(t('qqBridge.bridge.stopFailed'))
  } catch {
    ElMessage.warning(t('qqBridge.bridge.stopFailed'))
  }
}

async function onSaveBinding() {
  savingBinding.value = true
  try {
    // 按角色路由：清空 conversationId，桥接按 characterId 为每个 QQ 用户懒建专属会话，
    // 不再依赖固定会话号（清空上下文后会话失效时 404 自动 re-resolve，不受影响）。
    await store.setSettings({
      binding: {
        conversationId: '',
        characterId: bindingForm.characterId.trim(),
        allowMode: bindingForm.allowMode,
        allowUsers: splitList(bindingForm.allowUsers),
        allowGroups: splitList(bindingForm.allowGroups),
      },
    })
    ElMessage.success(t('qqBridge.binding.saved'))
    // allowlist 模式且白名单为空 → 无人放行，机器人不会回复任何 QQ 消息。
    // 主动告警，避免用户以为桥接坏了（main.js 在 characterId 已配时不再自动兜底 open）。
    if (bindingForm.allowMode === 'allowlist' && !bindingForm.allowUsers.trim() && !bindingForm.allowGroups.trim()) {
      ElMessage.warning(t('qqBridge.binding.emptyAllowlistWarning'))
    }
  } finally {
    savingBinding.value = false
  }
}

// 回复设置保存：展开当前 settings.reply 保留 timeoutMs 等 UI 未暴露字段，
// 再覆盖 segmentDelayMs/fallbackText；写回后提示「重启桥接生效」
// （setQqBridgeSettings 仅落盘不热重载，运行中的桥接仍用旧值）。
async function onSaveReply() {
  savingReply.value = true
  try {
    await store.setSettings({
      reply: {
        ...(settings.value.reply || {}),
        segmentDelayMs: Number(replyForm.segmentDelayMs) || 0,
        segmentJitterMs: Number(replyForm.segmentJitterMs) || 0,
        fallbackText: replyForm.fallbackText,
      },
    })
    ElMessage.success(t('qqBridge.reply.saved'))
  } finally {
    savingReply.value = false
  }
}

async function onSaveWs() {
  savingWs.value = true
  try {
    await store.setSettings({
      napcat: { wsUrl: wsForm.wsUrl.trim(), accessToken: wsForm.accessToken }
    })
    ElMessage.success(t('qqBridge.ws.saved'))
  } finally {
    savingWs.value = false
  }
}

// 桥接日志：页内结构化展示。startup.log 尾部 300 行 → 解析成 {time,tag,msg,level}，
// 按级别过滤（全部/成功/失败），time 转本地 HH:mm:ss，tag 用 el-tag，msg 按级别着色。
const logListRef = ref(null)
const logFilter = ref('all') // 'all' | 'success' | 'fail'
const rawLogLines = ref([])
const logsLoading = ref(false)

// 日志行解析：[ISOtimestamp] [tag] message → { time, tag, msg, level }
// 无 tag（如纯文本行）则 tag 留空；时间戳转本地 HH:mm:ss 便于快速扫读。
function parseLogLine(line) {
  const m = /^\[([^\]]+)\]\s*(?:\[([^\]]+)\]\s*)?(.*)$/.exec(line)
  if (!m) return { time: '', tag: '', msg: line, level: 'neutral' }
  let time = m[1] || ''
  const d = new Date(time)
  if (!isNaN(d.getTime())) time = d.toLocaleTimeString('default', { hour12: false })
  const tag = m[2] || ''
  const msg = m[3] || ''
  let level = 'neutral'
  // 成功：云端调用成功/会话创建/绑定解析/自动恢复/桥接拉起
  if (/cloud ok|created conversation|re-resolved|reuse conversation|resolved binding|resolved characterId|auto-recover|auto-started/i.test(msg)) {
    level = 'success'
  } else if (/reject|error:|failed|could not|returned no|no binding|no characterId|skip auto-bind|give up/i.test(msg)) {
    level = 'fail'
  }
  return { time, tag, msg, level }
}

const parsedLogs = computed(() => rawLogLines.value.map(parseLogLine))
const filteredLogs = computed(() =>
  logFilter.value === 'all' ? parsedLogs.value : parsedLogs.value.filter((l) => l.level === logFilter.value),
)

// 新日志到达后滚到底（最新在尾部），便于追踪实时事件
watch(rawLogLines, () => {
  nextTick(() => {
    const el = logListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

// 自动刷新：桥接运行时每 5s 拉取一次日志，便于实时追踪收发/重连事件
const autoRefresh = ref(true)
let logRefreshTimer = null
watch(
  [bridgeActive, autoRefresh],
  ([active, on]) => {
    if (logRefreshTimer) { clearInterval(logRefreshTimer); logRefreshTimer = null }
    if (active && on) {
      logRefreshTimer = setInterval(() => { refreshLogs().catch(() => {}) }, 5000)
    }
  },
  { immediate: true },
)

async function refreshLogs() {
  logsLoading.value = true
  try {
    const res = await store.getLogs()
    if (res?.ok) {
      rawLogLines.value = Array.isArray(res.lines) ? res.lines : []
    } else {
      rawLogLines.value = res?.error ? [t('qqBridge.logs.loadFail') + ': ' + res.error] : [t('qqBridge.logs.loadFail')]
    }
  } catch {
    rawLogLines.value = [t('qqBridge.logs.loadFail')]
  } finally {
    logsLoading.value = false
  }
}

onMounted(() => {
  if (isElectron) {
    store.syncFromMain()
    loadCharacterOptions()
    refreshLogs()
  }
})

onUnmounted(() => {
  if (logRefreshTimer) { clearInterval(logRefreshTimer); logRefreshTimer = null }
  store.dispose()
})
</script>

<style lang="scss" scoped>
.qq-bridge-page {
  max-width: $narrow-page-max;
}

.page-header {
  margin-bottom: $space-10;
}

.page-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.section {
  & + .section {
    margin-top: $space-12;
  }
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $space-4;
  margin-bottom: $space-6;
}

.section-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.section-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.qq-card {
  border-radius: $radius-lg;
  padding: $space-5 $space-6;
  display: flex;
  flex-direction: column;
  gap: $space-4;
}

.hint {
  color: $color-text-muted;
  font-size: $font-size-sm;
}

.field-hint {
  margin-top: $space-1;
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-normal;
}

.conv-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $space-2;
  padding: $space-3 $space-4;
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.host-actions {
  display: flex;
  gap: $space-2;
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.status-card {
  gap: $space-3;
}

.status-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: $space-3;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: $color-text-muted;

  &.is-ok {
    background: #4ade80;
    box-shadow: 0 0 6px rgba(74, 222, 128, 0.6);
  }
  &.is-err {
    background: $color-error;
  }
  &.is-off {
    background: $color-text-muted;
    opacity: 0.5;
  }
  &.is-busy {
    background: #fbbf24;
    animation: pulse 1.2s ease-in-out infinite;
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.status-label {
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text-primary;
}

.status-meta {
  font-size: $font-size-xs;
  color: $color-text-secondary;
  font-family: $font-mono;

  &.muted {
    color: $color-text-muted;
    font-family: inherit;
  }
  &.upgrade-hint {
    color: #fbbf24;
  }
}

.download-dialog-body {
  display: flex;
  flex-direction: column;
  gap: $space-4;
  padding: $space-2 0;
}

.download-phase {
  display: flex;
  align-items: center;
  gap: $space-2;
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text-primary;
}

.download-bytes {
  font-size: $font-size-xs;
  color: $color-text-muted;
  font-family: $font-mono;
  text-align: right;
}

.download-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-4;
}

.download-hint {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.binding-form,
.reply-form {
  :deep(.el-form-item) {
    margin-bottom: $space-4;
  }
  :deep(.el-form-item__label) {
    font-size: $font-size-xs;
    color: $color-text-muted;
  }
}

// 白名单双列：私聊/群并排，窄屏自动堆叠
.allowlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 $space-6;
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: $space-4;
  margin-top: $space-1;
}

// 回复设置：延迟数值输入（弃用滑块——Electron 下 el-slider 拖拽不可靠，
// 改 el-input-number 让用户直接填数值，+/- 步进 100ms，更稳更直观）
.delay-row {
  display: flex;
  align-items: center;
  gap: $space-3;
}

.delay-input {
  width: 180px;
}

.delay-unit {
  font-family: $font-mono;
  font-size: $font-size-sm;
  color: $color-text-secondary;
}

.restart-hint {
  margin: 0;
  color: #fbbf24;
}

// 桥接日志：页内结构化卡片
.logs-card {
  gap: $space-3;
}

.logs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-3;
  flex-wrap: wrap;
}

.logs-toolbar-right {
  display: flex;
  align-items: center;
  gap: $space-4;
}

.logs-count {
  font-size: $font-size-xs;
  color: $color-text-muted;
  font-family: $font-mono;
}

.logs-empty {
  padding: $space-8 $space-4;
  text-align: center;
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.logs-list {
  max-height: 480px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: $space-1;
  padding: $space-2 $space-3;
  background: var(--el-fill-color-light);
  border-radius: $radius-sm;
}

.log-line {
  display: flex;
  align-items: baseline;
  gap: $space-2;
  font-size: $font-size-xs;
  line-height: 1.6;
  font-family: $font-mono;
}

.log-time {
  flex-shrink: 0;
  color: $color-text-muted;
}

.log-tag {
  flex-shrink: 0;
  transform: translateY(1px);
}

.log-msg {
  flex: 1;
  min-width: 0;
  word-break: break-word;
  color: $color-text-secondary;
}

.log-line.is-success .log-msg {
  color: #4ade80;
}

.log-line.is-fail .log-msg {
  color: $color-error;
}
</style>
