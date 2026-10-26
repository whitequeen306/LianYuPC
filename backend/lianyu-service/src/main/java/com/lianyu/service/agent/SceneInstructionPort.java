package com.lianyu.service.agent;

/**
 * 场景 instruction 文案的端口（实现方在 lianyu-web，走 Python 的
 * {@code /agent/prompt/scene-instruction}）。
 *
 * <p>为什么要端口而不是直接调 agent：{@code lianyu-service} 不能反向依赖
 * {@code lianyu-web}（依赖方向是 web → service）。与 {@link AgentTextPort}、
 * {@code TtsPort} 同一套做法。
 *
 * <p><b>文案所有权在 Python</b>：Java 只传场景 + 语言，不许在这里写任何 prompt 文本
 * —— 原来 {@code CharacterDiaryService} 里的四语 switch 就是反面教材。
 */
public interface SceneInstructionPort {

    /**
     * @param scene          场景名，如 {@code DIARY}
     * @param outputLanguage 输出语言码（zh / zh-TW / ja / en）
     * @return instruction 文本；取不到返回 {@code null} 或空串（调用方应跳过，不要编一段兜底文案）
     */
    String instruction(String scene, String outputLanguage);

    /**
     * 按名渲染一段 prompt 资源（模板 + 变量）。
     *
     * <p>与 {@link #instruction} 的区别：那个按「场景」取固定文案，这个按「资源名 + 变量」
     * 渲染模板（例如主动发言任务块要带 {@code max_pieces}）。
     *
     * @param name            {@code app/prompts/} 下的文件名（不含 .md）
     * @param variables       模板里的 {@code {name}} 变量；参数名必须与模板严格一致
     * @param leadingNewlines 结果前导空行数。**必须逐站点量** —— 各处 Java 字面量并不一致，
     *                        统一按 2 处理会悄悄改动模型看到的内容
     * @return 渲染结果；取不到返回 null（调用方应跳过该次生成，不要编兜底文案）
     */
    String render(String name, java.util.Map<String, Object> variables, int leadingNewlines);
}
