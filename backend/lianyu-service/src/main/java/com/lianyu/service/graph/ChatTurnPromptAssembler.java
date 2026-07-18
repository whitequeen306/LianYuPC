package com.lianyu.service.graph;

import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterCitySettingsService;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.conversation.ProactiveRealWorldContextService;
import com.lianyu.service.conversation.SessionSummaryService;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import java.time.LocalTime;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scene-aware system prompt assembly used by ChatTurn Graph nodes.
 * Centralizes sections formerly split across ConversationService / Moments / Group / Diary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnPromptAssembler {

    private static final Pattern GOODNIGHT_KEYWORDS = Pattern.compile(
            "晚安|睡了|睡觉|眠い|おやすみ|good\\s*night|nighty|night night|gn|安|困了|我要睡了|先睡了|去睡了|睡吧");

    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final RelationshipStateService relationshipStateService;
    private final SessionSummaryService sessionSummaryService;
    private final OutputLanguageService outputLanguageService;
    private final TimeTool timeTool;
    private final CharacterRecentActivityService characterRecentActivityService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final ProactiveRealWorldContextService proactiveRealWorldContext;

    public record GroupExtras(int maxPieces, String otherCharactersLine, String mentionCtx) {
    }

    public record AssembledPrompt(
            String systemPrompt,
            String memoryBlock,
            String relationshipBlock,
            String sessionSummaryBlock,
            String outputLanguage
    ) {
    }

    public AssembledPrompt assemble(
            ChatTurnScene scene,
            Long userId,
            Long conversationId,
            Character character,
            String userInputForLang,
            String lastUserMessageForMemory,
            String extraSystemSuffix,
            GroupExtras groupExtras
    ) {
        String lang = outputLanguageService.resolveForRequest(userId, userInputForLang);
        String memoryBlock = memoryRetriever.retrieveProfileContext(
                character.getId(), userId, lastUserMessageForMemory);

        String relationshipBlock = "";
        if (scene.includeRelationship()) {
            String rel = relationshipStateService.buildPromptContext(userId, character.getId());
            if (rel != null && !rel.isBlank()) {
                relationshipBlock = rel;
            }
        }

        String sessionBlock = "";
        if (scene.includeSessionSummary() && conversationId != null) {
            String s = sessionSummaryService.formatForPrompt(conversationId);
            if (s != null && !s.isBlank()) {
                sessionBlock = s;
            }
        }

        String memoryContext = joinBlocks(memoryBlock, relationshipBlock, sessionBlock);
        String base = promptBuilder.buildSystemPrompt(
                character, memoryContext, lang, scene.enableChatTools());

        if (scene.includeTimeCityGoodnight()) {
            base = appendCurrentTimeContext(base);
            base = appendRecentActivityContext(base, userId, character.getId(), lang);
            base = appendCurrentRealCityContext(base, character);
            base = appendGoodnightContextIfApplicable(base, userInputForLang, lang);
            base = enforceNaturalChatStyle(base, lang, character);
        } else if (scene == ChatTurnScene.MOMENTS || scene == ChatTurnScene.DIARY) {
            base = enforceNaturalChatStyle(base, lang, character);
        }

        if (scene == ChatTurnScene.GROUP && groupExtras != null) {
            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            int maxPieces = groupExtras.maxPieces() > 0
                    ? groupExtras.maxPieces()
                    : behavior.maxRepliesPerTurn();
            base = promptBuilder.appendGroupChatInstructions(
                    base,
                    character,
                    maxPieces,
                    groupExtras.otherCharactersLine(),
                    groupExtras.mentionCtx(),
                    lang);
        }

        if (scene.includeProactiveRealWorld()) {
            base = base + proactiveRealWorldContext.buildBlock(character);
        }

        if (extraSystemSuffix != null && !extraSystemSuffix.isBlank()) {
            base = base + extraSystemSuffix;
        }

        log.debug("ChatTurn prompt assembled: scene={}, characterId={}, hasMemory={}, hasRel={}, hasSession={}",
                scene, character.getId(),
                memoryBlock != null && !memoryBlock.isBlank(),
                !relationshipBlock.isBlank(),
                !sessionBlock.isBlank());

        return new AssembledPrompt(base, nullToEmpty(memoryBlock), relationshipBlock, sessionBlock, lang);
    }

    private static String joinBlocks(String... blocks) {
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            if (block == null || block.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(block);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String appendCurrentTimeContext(String basePrompt) {
        String timeFact = timeTool.readCurrentTimeFact();
        return basePrompt + """


                === 当前真实环境 ===
                """ + timeFact + """

                注意：上方对话记录中的消息可能发生在更早的时刻（例如昨晚）。判断「现在」是白天还是夜晚、今天星期几、是否跨天等，必须以本条中的当前真实时间为准，不要根据旧对话的语气或内容臆测当前时刻。""";
    }

    private String appendRecentActivityContext(String basePrompt, Long userId, Long characterId, String lang) {
        String block = characterRecentActivityService.formatForPrompt(userId, characterId, lang);
        if (block == null || block.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n" + block;
    }

    private String appendCurrentRealCityContext(String basePrompt, Character character) {
        Map<String, Object> settings = character != null ? character.getSettings() : null;
        if (!CharacterCitySettingsService.MODE_REAL.equals(CharacterCitySettingsService.resolveCityMode(settings))) {
            return basePrompt;
        }
        String city = CharacterCitySettingsService.resolveRealCity(settings);
        if (city.isBlank()) {
            return basePrompt;
        }
        return basePrompt + """


                === 用户当前所在现实城市（权威） ===
                """ + city + """

                注意：对话历史中若提到用户还在其他城市、或基于旧城市的天气/当地情况，一律视为过时信息。
                涉及用户所在地、搬迁、当地天气与时区等，必须以本条中的当前城市为准，不要沿用历史里的旧城市。""";
    }

    private String appendGoodnightContextIfApplicable(String basePrompt, String userInput, String lang) {
        if (userInput == null || userInput.isBlank()) {
            return basePrompt;
        }
        if (!isGoodnightTime() || !containsGoodnightKeyword(userInput)) {
            return basePrompt;
        }
        return switch (OutputLanguage.fromCode(lang)) {
            case JA -> basePrompt + """

                    === おやすみモード（最優先） ===
                    ユーザーが「おやすみ」を言った。あなたはとても優しく、落ち着いた口調で応答する。
                    静かに相手を眠りに誘い、短く温かいおやすみの言葉をかける。長文は避け、1〜3文で。
                    子守唄や寝物語を求められたら短く応じてもよい。""";
            case EN -> basePrompt + """

                    === Goodnight Mode (Highest Priority) ===
                    The user just said goodnight. Respond with your gentlest, most soothing voice.
                    Help them drift off to sleep with a short, warm goodnight message. Keep it to 1-3 sentences.
                    If they ask for a lullaby or bedtime story, you may provide a short one.""";
            case ZH_TW -> basePrompt + """

                    === 晚安模式（最高優先） ===
                    用戶剛剛說了晚安。用你最溫柔、最平靜的語氣回應。
                    輕輕哄對方入睡，送上一句溫暖的晚安。控制在1-3句話，不要長篇大論。
                    如果對方想聽睡前故事或搖籃曲，可以簡短回應。""";
            default -> basePrompt + """

                    === 晚安模式（最高优先级） ===
                    用户刚刚说了晚安。用你最温柔、最平静的语气回应。
                    轻轻哄对方入睡，送上一句温暖的晚安。控制在1-3句话，不要长篇大论。
                    如果对方想听睡前故事或摇篮曲，可以简短回应。""";
        };
    }

    private boolean isGoodnightTime() {
        int minutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        return minutes >= 23 * 60 || minutes < 8 * 60;
    }

    private boolean containsGoodnightKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return GOODNIGHT_KEYWORDS.matcher(text.toLowerCase()).find();
    }

    private String enforceNaturalChatStyle(String basePrompt, String languageCode, Character character) {
        String prompt = basePrompt == null ? "" : basePrompt;
        boolean showInnerThoughts = CharacterPreferenceResolver.showInnerThoughts(character);
        return prompt + outputLanguageService.buildNaturalStyleBlock(languageCode, showInnerThoughts);
    }
}
