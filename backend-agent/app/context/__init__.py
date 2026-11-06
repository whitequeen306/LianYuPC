"""上下文预算与组装（架构改革 v1.0 的落点）。

只放「怎么把一轮上下文装进预算」的纯逻辑：token 估算、槽位预算、
场景卡与装配器。取数仍留在 `app.data_readers`，渲染仍留在
`app.system_prompt` 与 `app.prompt_assembly`，避免再造一个事实来源。
"""
