场景任务块（scene task blocks）——从 Java 逐字移植的 prompt 资源。

来源与对应关系（迁移时逐字对照，改动任一处必须双端同步 + 快照测试更新）：
- proactive_task.md          ← ProactiveMessageService.buildProactiveTaskSystemSuffix
- cold_open_task.md          ← sendColdOpenFirstLine 内联文本块
- cold_open_followup.md      ← sendColdOpenFollowUpIfStillSilent 内联文本块
- city_change_task.md        ← sendCityChangeFollowUp 内联文本块（%s 占位）
- diary_instruction_zh.md    ← CharacterDiaryService instruction default 分支
- diary_instruction_ja.md    ← JA 分支
- diary_instruction_en.md    ← EN 分支
- diary_instruction_zh_tw.md ← ZH_TW 分支

红线（资产清单第 3 条）：这些块一律拼在 system 消息里；
trailing user turn 永远是 PROACTIVE_USER_TRIGGER（"（系统触发主动发言，用户未发送新消息。）"），
任务说明绝不放进 user 消息——历史上模型把编号要求当作用户乱码指令。
