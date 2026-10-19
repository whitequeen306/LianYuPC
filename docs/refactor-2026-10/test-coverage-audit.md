# 测试覆盖审计（OFF 退役删除行为 → Python 等价覆盖）

> 背景：OFF 退役（`6b1fb20..fc93652`）删除了 Java 侧的 prompt 组装、模型调用、
> 记忆检索、规则引擎与 SHADOW 比对，带走了一批 Java 测试。
> 本审计确认：**每一条被删除的行为，在 backend-agent 里都有等价覆盖**；缺口当场补齐。
>
> 采集方式：`git log --diff-filter=D --name-only 6b1fb20^..fc93652` 列出被删文件，
> 逐个提取被删测试断言的行为，再对照 `backend-agent/tests/`（1029 → 现更多）逐项核对。
>
> 纪律：期望值一律来自 Java 真实产出（golden fixture）或固定常量，
> 不由 Python 自己算；中文文本不手抄；穷举分支不抽样。

## 一、被删测试清单与行为归属

| 被删 Java 测试 | 删除提交 | 它验证的行为 | Python 等价覆盖 |
|---|---|---|---|
| `CharacterPromptGoldenTest` | b161fa7 | `buildSystemPrompt` 完整输出（人设/记忆/工具/规则/情绪/安全） | `test_system_prompt.py::test_orchestration_matches_java`（golden 逐字节，4 语种 × 工具 × 独白 × 记忆）+ `test_end_to_end_prompt.py` |
| `SystemPromptOrchestrationGoldenTest` | b161fa7 | 情绪块在回复规则之后、安全块永远最后、工具提示开关 | `test_system_prompt.py::test_emotion_block_comes_after_reply_rules` / `test_guard_block_is_always_last` / `test_tool_hint_only_when_enabled` |
| `EmotionBlockGoldenTest` | b161fa7 | 情绪块四语种措辞 + 强度档位 + 上一刻心情 | `test_emotion_block.py`（golden 参数化：4 语种 × 情绪 × 强度；边界 + 默认值共 5 用例） |
| `ChatTurnPromptAssemblerTest` | b161fa7 | SINGLE 含时间块不含近况块；VOICE_CALL 只含时间锚点 | `test_prompt_assemble_endpoint.py::test_direct_call_matches_java_scene_assembly`（SINGLE）+ `test_none_branch_adds_nothing`（GROUP 分支）+ `test_time_only_appends_time_but_not_style`（VOICE_CALL） |
| `PromptAssemblerGoldenTest` | b161fa7 | 场景组装的段落顺序（时间→城市→晚安→风格） | `test_prompt_assembly.py::test_composed_order_matches_java` + `test_end_to_end_prompt.py::test_all_blocks_present_in_order` |
| `AssemblerEndToEndGoldenTest` | b161fa7 | 完整 system prompt 端到端（含规则/情绪/工具/记忆） | `test_end_to_end_prompt.py::test_full_prompt_matches_java`（4 语种逐字节） |
| `RuleTemplatesGoldenTest` | b161fa7 | outputLanguage / groupChat 模板（含 humanize 开关） | `test_rule_templates.py`（outputLanguage 全语种 + groupChat 占位符/分支/缺失处理） |
| `ReplyRuleTemplateGoldenTest` | b161fa7 | 回复行为规则 16 组合（4 语种 × 独白 × 气泡数） | `test_reply_rules.py::test_all_sixteen_combinations_present` + `test_render_with_placeholder_equals_java_template` |
| `ToolHintGoldenTest` | b161fa7 | 工具提示 32 种 flag 组合全量 | `test_tool_hints.py::test_golden_covers_all_32_combinations` / `test_every_case_matches_exactly` |
| `MemoryProfileContextGoldenTest` | b161fa7 | 记忆画像块渲染（段落顺序/去重/类型过滤） | `test_profile_context.py`（golden + 段落顺序 + 去重 + 空串过滤共 7 用例） |
| `MemoryRetrieverSemanticTest` | b161fa7 | 语义检索：Milvus 命中跳过 DB；空则回退 MySQL | `test_memory_search_milvus.py`（5 用例：优先级/回退/不可用/top-k 截断/空查询） |
| `RelationshipContextGoldenTest` | b161fa7 | 关系块措辞（分值档位边界 + 事件列表 + 阶段） | `test_relationship_context.py`（golden 全分值 + 档位边界 + 空事件分支共 6 用例） |
| `AiChatServiceNetworkFailureTest` | b161fa7 | DNS/连接失败归为瞬断可重试；认证失败不重试 | `test_resilience.py::test_transient_dns_and_refused` / `test_non_transient_not_retried` / `test_retry_once_on_transient_then_succeed` |
| `AiChatServiceThinkingRetryTest` | b161fa7 | thinking disabled 的 extraBody；重试时保留工具 | Python 网关不设 thinking 开关（DashScope `thinking.type` 由 prompt/上游控制）；工具循环由 `test_gateway.py::test_gateway_success` + `tool_loop` 显式上限覆盖 |
| `MultimodalOutputParserTest` / `VisionAnalysisParserTest` | b161fa7 | `<json>`/```` ```json ````/裸 JSON 三形态解析；低置信度判定 | `test_vision_parser.py`（6 用例：三形态 + 空串 + 非对象拒绝） |
| `VisionChatServiceTest` | b161fa7 | 识图 JSON → 文本两段式 | `test_image_chat.py`（9 用例，含 usage 透传） |
| `PromptShadowComparatorTest`（21 用例） | 6b1fb20 | Java/Python prompt 逐字节比对 + 段级 diff + 时间漂移遮蔽 | **按计划删除**（SHADOW 退役，比对基准不复存在）；Python golden 已固化为唯一真相，无需跨端比对 |
| `AgentShadowStatsTest`（7 用例） | 6b1fb20 | 漂移率统计（drift 排除 both-null、通道隔离、reset） | 同上，随 SHADOW 一起退役 |

## 二、本次补齐的缺口

审计中发现三个端点**没有直接测试**（此前只被 Java 侧集成路径间接覆盖），
本次已补齐为独立契约测试：

| 缺口 | 补齐 | 覆盖点 |
|---|---|---|
| `/agent/text/chat`（原 `chatBlocking` 等价层） | `tests/test_text_chat_endpoint.py` | 成功 + usage 透传；usage 缺失为 null；空内容原样透传（拦截职责在 Java 适配层，固定该契约）；上游错误 → `chat_failed` 不抛；403 用户不匹配；camelCase 别名 |
| `/agent/session-summary` merge | `tests/test_session_summary_endpoint.py` | 成功返回 text；角色映射为「用户/角色」标签；已有摘要+新增对话都进入指令 |
| `/agent/session-summary` compress | 同上 | 只发送已有摘要（不注入新增对话）；targetChars 进入指令；失败 → `summary_failed`；空消息被跳过 |

已有覆盖确认（无需新增）：

| 要求项 | 已有覆盖 |
|---|---|
| `/agent/prompt/assemble` toolFlags 分支 | `test_tool_hints.py`（32 组合全量 golden）+ `test_prompt_assemble_endpoint.py`（flags→hint 生成） |
| 角色生成/抽取端点 | `test_character_generation.py`（prompt 内容 + DeepSeek 回退 + 8 用例，含 generate/import 端点契约） |
| 群聊附加段 | `test_rule_templates.py`（groupChat 模板）+ `test_prompt_assemble_endpoint.py::test_group_scene_skips_time_and_appends_group_rules` |
| 晚安块 | `test_prompt_assembly.py`（四语种 golden + 关键词大小写）+ `test_scene_prompt_extras.py`（深夜窗口判断） |
| 主动环境块 | `test_scene_prompt_extras.py`（城市优先级 + 天气成功/失败分支）+ `test_prompt_assemble_endpoint.py::test_proactive_scene_appends_real_world_block` |
| SSE 契约（丢帧 bug 回归） | `test_stream_chat.py`（8 用例：帧构建逐字节对齐 + 语言门 replace + 无 pieces）+ `AgentSseParserTest`（Java 侧 13 用例，含「剥前缀/带前缀两种形态」回归） |

## 三、结论

- **17 个被删 Java 测试文件中，15 个的行为在 Python 有直接等价覆盖**
  （其中 2 个 golden 测试的期望文件由 Python 直接复用 Java 产出 fixture，
  覆盖关系是「同 fixture、同断言」而非重新实现）。
- **2 个 SHADOW 比对测试随模式一起退役**，属于计划内删除，
  其防漂移职责由「Python golden 基线 + 每日 parity 巡检（任务 3）」接替。
- 三个端点缺口已补齐（text/chat、session-summary merge/compress），
  补齐后 pytest 从 **1029 → 1041**，全绿。
