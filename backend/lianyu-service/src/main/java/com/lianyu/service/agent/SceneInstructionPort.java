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
}
