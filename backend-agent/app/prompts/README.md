场景任务块（scene task blocks）——从 Java 逐字移植的 prompt 资源。

来源与对应关系（迁移时逐字对照，改动任一处必须双端同步 + 快照测试更新）：
- proactive_task.md          ← ProactiveMessageService.buildProactiveTaskSystemSuffix
- cold_open_task.md          ← sendColdOpenFirstLine 内联文本块
- cold_open_followup.md      ← sendColdOpenFollowUpIfStillSilent 内联文本块
- city_change_task.md        ← sendCityChangeFollowUp 内联文本块（%s 占位）
- city_change_context.md     ← appendCityChangeContext（拼接块，无尾换行）
- group_proactive_task_zh.md / _zh_tw.md / _ja.md / _en.md
                             ← GroupChatService.sendProactiveGroupMessage 的四语 switch
                               + 段头/禁止条款拼接（**段头与禁止条款在四语下都是中文**；
                               正文与「最近气氛参考」之间是 3 个换行，原文如此）
- diary_instruction_zh.md    ← CharacterDiaryService instruction default 分支
- diary_instruction_ja.md    ← JA 分支
- diary_instruction_en.md    ← EN 分支
- diary_instruction_zh_tw.md ← ZH_TW 分支

红线（资产清单第 3 条）：这些块一律拼在 system 消息里；
trailing user turn 永远是 PROACTIVE_USER_TRIGGER（"（系统触发主动发言，用户未发送新消息。）"），
任务说明绝不放进 user 消息——历史上模型把编号要求当作用户乱码指令。

另：`ProactiveRealWorldContextService` 那段「主动开口参考环境」块**不在**本目录——
它有「天气实况 / 未取到」条件分支，属计算块，住在 `app/proactive_context.py`。
