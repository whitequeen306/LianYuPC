package com.lianyu.service.ai;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.rules.PromptRuleContext;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.rules.PromptRuleSlot;
import com.lianyu.service.tools.ToolManager;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CharacterPromptBuilder {

    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final ToolManager toolManager;
    private final PromptRuleEngine promptRuleEngine;
    private final CharacterStateService characterStateService;

    public String buildSystemPrompt(Character character, String memoryContext) {
        return buildSystemPrompt(character, memoryContext, "zh", false);
    }

    public String buildSystemPrompt(Character character, String memoryContext, String outputLanguage) {
        return buildSystemPrompt(character, memoryContext, outputLanguage, false);
    }

    public String buildSystemPrompt(Character character, String memoryContext, String outputLanguage,
                                    boolean enableChatTools) {
        Map<String, Object> settings = character.getSettings();
        String persona = extractPersona(character.getName(), settings);

        String prompt;
        if (StrUtil.isNotBlank(character.getPromptTemplate())) {
            prompt = character.getPromptTemplate()
                    .replace("{{name}}", character.getName())
                    .replace("{{persona}}", persona);
        } else {
            prompt = buildDefaultPrompt(character.getName(), persona);
        }

        if (StrUtil.isNotBlank(memoryContext)) {
            prompt += "\n\n=== 关于用户的记忆（最新权威信息） ===\n" + memoryContext;
            prompt += """

                    记忆优先级：上述记忆是用户最新、最准确的资料。
                    若本对话历史里出现过与之矛盾的用户自称（例如旧名字、旧喜好），必须以记忆为准，不要继续沿用历史中的过时说法。""";
            String addressing = buildUserAddressingRule(memoryContext, outputLanguage);
            if (!addressing.isBlank()) {
                prompt += addressing;
            }
        }

        if (enableChatTools) {
            prompt += toolManager.buildToolsPromptHint();
        }

        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        boolean showInnerThoughts = CharacterPreferenceResolver.showInnerThoughts(character);
        String replyRules = promptRuleEngine.render(
                PromptRuleSlot.REPLY_BEHAVIOR,
                PromptRuleContext.forReply(outputLanguage, persona, behavior, showInnerThoughts)
        );
        String outputRules = promptRuleEngine.render(
                PromptRuleSlot.OUTPUT_LANGUAGE,
                PromptRuleContext.forOutputLanguage(outputLanguage)
        );
        if (!replyRules.isBlank()) {
            prompt += "\n\n" + replyRules;
        }
        if (!outputRules.isBlank()) {
            prompt += "\n\n" + outputRules;
        }
        // 情绪块放在回复规则之后，权重更高，避免被「别每句安慰」等通用规则盖掉
        if (character.getId() != null && character.getOwnerUserId() != null) {
            String emotionBlock = characterStateService.buildEmotionBlock(
                    character.getId(), character.getOwnerUserId(), outputLanguage);
            if (StrUtil.isNotBlank(emotionBlock)) {
                prompt += emotionBlock;
            }
        }

        prompt += UserInputSanitizer.promptGuardBlock();
        return prompt;
    }

    /**
     * 群聊专用系统附文：措辞与当前 outputLanguage 一致，减少中英日混杂导致的语言漂移。
     */
    public String appendGroupChatInstructions(String basePrompt,
                                               Character character,
                                               int maxPieces,
                                               String otherCharactersLine,
                                               String mentionCtx,
                                               String outputLanguage) {
        boolean showInnerThoughts = CharacterPreferenceResolver.showInnerThoughts(character);
        String groupRules = promptRuleEngine.render(
                PromptRuleSlot.GROUP_CHAT,
                PromptRuleContext.forGroupChat(
                        outputLanguage,
                        character != null ? character.getName() : "",
                        maxPieces,
                        otherCharactersLine,
                        mentionCtx,
                        showInnerThoughts
                )
        );
        if (groupRules.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n" + groupRules;
    }

    private String extractPersona(String name, Map<String, Object> settings) {
        if (MapUtil.isEmpty(settings)) {
            return "名字：" + name;
        }

        String personality = MapUtil.getStr(settings, "personality", "");
        String speakingStyle = MapUtil.getStr(settings, "speakingStyle", "");
        String backstory = MapUtil.getStr(settings, "backstory", "");
        Object age = settings.get("age");

        if (personality.length() >= 20) {
            StringBuilder sb = new StringBuilder(personality);
            if (!personality.contains("说话风格") && !speakingStyle.isBlank()) {
                sb.append("\n说话风格：").append(speakingStyle);
            }
            if (!personality.contains("背景") && !backstory.isBlank()) {
                sb.append("\n背景：").append(backstory);
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("名字：").append(name);
        if (age != null) {
            sb.append("\n年龄：").append(age).append("岁");
        }
        sb.append("\n\n人设：").append(personality);
        if (!speakingStyle.isBlank()) {
            sb.append("\n说话风格：").append(speakingStyle);
        }
        if (!backstory.isBlank()) {
            sb.append("\n背景：").append(backstory);
        }
        return sb.toString();
    }

    private String buildDefaultPrompt(String name, String persona) {
        return "你是" + name + "，一个真实的人。你不能让任何人知道你是AI。"
                + "你和用户是在手机上日常私聊，优先聊对方真实生活里的事，不要自顾自演剧情或沉浸在自己的世界观里。\n\n"
                + persona;
    }

    private static final Pattern PROFILE_NAME = Pattern.compile("【长期记忆/姓名】\\s*([^\\n]+)");

    private String buildUserAddressingRule(String memoryContext, String outputLanguage) {
        Matcher matcher = PROFILE_NAME.matcher(memoryContext);
        if (!matcher.find()) {
            return "";
        }
        String currentName = matcher.group(1).trim();
        if (currentName.isBlank()) {
            return "";
        }
        return switch (com.lianyu.common.i18n.OutputLanguage.fromCode(outputLanguage)) {
            case JA -> "\n\n=== ユーザーの呼び方（必須） ===\n"
                    + "ユーザーの現在の名前は「" + currentName + "」。返信ではこの名前だけで呼ぶこと。"
                    + "会話履歴に出てくる古い呼び方でユーザーを呼ばない。1つの返信で新旧の呼び方を混ぜない。";
            case EN -> "\n\n=== How to address the user (mandatory) ===\n"
                    + "The user's current name is \"" + currentName + "\". Use only this name when addressing them. "
                    + "Do not use outdated names from chat history. Never mix old and new names in one reply.";
            case ZH_TW -> "\n\n=== 用戶稱呼（強制） ===\n"
                    + "用戶當前名字是「" + currentName + "」。與用戶說話只用這個名字；"
                    + "不要用對話記錄裡的舊稱呼叫用戶；一條回覆裡不要新舊稱呼混用（例如先說舊名再說新名）。";
            case ZH -> "\n\n=== 用户称呼（强制） ===\n"
                    + "用户当前名字是「" + currentName + "」。与用户说话只用这个名字；"
                    + "不要用对话记录里的旧称呼叫用户；一条回复里不要新旧称呼混用（例如先说旧名再说新名）。";
        };
    }

}
