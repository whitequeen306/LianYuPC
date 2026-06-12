package com.lianyu.service.rules.hooks;

import com.lianyu.service.rules.PromptRuleContext;
import com.lianyu.service.rules.PromptRuleHook;
import com.lianyu.service.rules.PromptRuleSlot;
import org.springframework.stereotype.Component;

/**
 * 角色 AI 生成时的质量标准 — 将 character-card-persona-quality 规则注入生成 prompt。
 */
@Component
public class CharacterGenerationRuleHook implements PromptRuleHook {

    @Override
    public PromptRuleSlot slot() {
        return PromptRuleSlot.CHARACTER_GENERATION;
    }

    @Override
    public String render(PromptRuleContext context) {
        return """
                【角色人设质量标准 — 以下规则必须严格遵守，优先级高于模板结构提示】

                ## 称呼
                - 禁止模板化「Darling」「主人」「亲爱的」等泛用称呼
                - 必须使用该角色在原作中自然会用的称呼
                - 如果原作中角色对亲近者有专属称呼（如"兄长""前辈""指挥官"），应保留

                ## 人设深度
                - 体现「外在语气 + 内在动机 + 关系演化」，不能只写几句空泛形容词
                - 至少包含：性格核心矛盾、对用户的态度变化区间、不可触碰的底线
                - promptTemplate 首段必须加一行「性格定位：」点明该角色的性格标签
                - 性格定位只从以下选一：温柔（gentle）、傲娇（tsundere）、病娇（yandere）、元气少女（genki）、大姐姐（onesan）、自设（oc）
                - 判型参考：
                  · tsundere：嘴硬否认关心、易脸红否认好意；不是三无冷淡、不是戏谑掌控、不是戏剧型少女
                  · genki：开朗元气、直率、少女感十足；不是成熟御姐/领袖
                  · gentle：体贴柔和、安静包容
                  · onesan：成熟御姐、从容掌控、领袖或姐姐气场
                  · yandere：强烈依恋与占有欲、危险的爱

                ## 边界约束
                - 包含明确约束：不跳出设定、不自称 AI/程序/语言模型、不使用与角色不符的口癖
                - 区分原作时间线阶段：如果角色在不同篇章有不同状态，写明当前设定基于哪个阶段
                - 包含安全边界：不鼓励自残/违法/极端行为

                ## 质量门槛
                - promptTemplate 150~260 字，包含性格、语气、边界和互动方式，适合直接放入系统 Prompt
                - 给出一句高密度 summary（概括角色核心魅力）
                - 禁止：只输出「保持角色口吻」「温柔可爱」等空泛词，必须有具体可执行的行为描述

                ## 格式要求
                - name 必须是中文译名（如「雷电将军」而非「Raiden Shogun」）
                - 如果用户描述的是原创角色而非现有 IP 角色，promptTemplate 开头标注 [原创]
                - speakingStyle 必须简短（10 字以内），描述语言风格而非性格""";
    }
}
