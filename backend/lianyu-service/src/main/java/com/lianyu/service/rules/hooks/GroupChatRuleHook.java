package com.lianyu.service.rules.hooks;

import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.service.rules.PromptRuleContext;
import com.lianyu.service.rules.PromptRuleHook;
import com.lianyu.service.rules.PromptRuleSlot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GroupChatRuleHook implements PromptRuleHook {

    @Value("${lianyu.chat.humanize.enabled:true}")
    private boolean humanizeEnabled;
    @Override
    public PromptRuleSlot slot() {
        return PromptRuleSlot.GROUP_CHAT;
    }

    @Override
    public String render(PromptRuleContext context) {
        String name = context.characterName() == null ? "" : context.characterName();
        String mention = context.mentionContext() == null ? "" : context.mentionContext();
        String other = context.otherCharactersLine() == null ? "" : context.otherCharactersLine();
        int maxPieces = context.maxPieces() == null ? 2 : context.maxPieces();

        return switch (OutputLanguage.fromCode(context.outputLanguage())) {
            case JA -> """
                    === グループチャット ===
                    あなたはグループチャットにいる。""" + other + "\n"
                    + "自然に会話に参加し、他キャラにも話しかけ、ユーザにも返信してよい。\n"
                    + "1～" + maxPieces + " 通の短文を連続送信してよい（LINEのように）。複数なら空行で区切る。"
                    + "1通の中では改行しない。\n"
                    + "\n=== @メンション（重要）===\n"
                    + "1) 「@表示名」で相手を指名できる（表示名はシステムで与えられた表記のまま）。\n"
                    + "2) @された側はその後優先して反応しやすい。\n"
                    + "3) 誰かに答えを求める・話を振るときは@を優先。\n"
                    + "4) 自分が@されたら、その件を優先してから補足する。\n"
                    + "5) @は控えめに：1メッセージにつき必要なときだけ最大1人。\n\n"
                    + "発話制約：自分（" + name + "）としてのみ話す。"
                    + "他キャラになりすます・「名前:セリフ」の脚本形式は禁止。\n"
                    + mention;
            case EN -> """
                    === Group chat ===
                    You are in a group chat. """ + other + "\n"
                    + "Participate naturally: address other characters or reply to the user.\n"
                    + "You may send 1~" + maxPieces + " short messages in a row (quick chat bubbles); "
                    + "separate multiple messages with blank lines; never use line breaks inside one message.\n"
                    + "\n=== @ Mentions (important) ===\n"
                    + "1) Ping someone using \"@DisplayName\" exactly as assigned by the system.\n"
                    + "2) Mentioned participants are likelier to pick up your thread soon.\n"
                    + "3) When asking someone or handing off a topic, prefer @.\n"
                    + "4) If you were @'d, address that first, then add your thoughts.\n"
                    + "5) Use mentions sparingly: at most one @ per message, only when needed.\n\n"
                    + "You must speak only as yourself (" + name + "). Never impersonate other characters "
                    + "or format lines like \"Name: spoken line\". No multi-character script layouts.\n"
                    + mention;
            case ZH_TW -> """
                    === 群聊資訊 ===
                    你現在在一個群聊中。""" + other + "\n"
                    + "請自然參與群聊討論，可以對其他角色說話，也可以回應用戶。\n"
                    + "你可以連續發 1~" + maxPieces + " 條短訊息（像真人連發訊息），多條時必須用空行分隔；"
                    + "同一條訊息內部不要換行，一段話寫完整。\n"
                    + "\n=== @機制（非常重要）===\n"
                    + "1) 你擁有 @別人的權力。可使用「@角色名」點名某個對象；\n"
                    + "2) 被你@到的對象會在後續輪次更容易優先關注並回應你；\n"
                    + "3) 當你希望某個角色回答問題、接住話題或繼續互動時，應優先使用@；\n"
                    + "4) 若有人@了你，請優先回應對方關切，再補充你自己的觀點；\n"
                    + "5) @要精準、克制：每條訊息最多@1人，只有在確實需要點名時才用，避免無意義連環@。\n\n"
                    + "表達約束：只能以你自己（" + name + "）的身份發言，"
                    + "嚴禁代替其他角色發言，嚴禁輸出「角色名: 台詞」的多人劇本格式。\n"
                    + mention;
            case ZH -> """
                    === 群聊信息 ===
                    你现在在一个群聊中。""" + other + "\n"
                    + "请自然参与群聊讨论，可以对其他角色说话，也可以回应用户。\n"
                    + "你可以连续发 1~" + maxPieces + " 条短消息（像真人连发微信），多条时必须用空行分隔；"
                    + "同一条消息内部不要换行，一段话写完整。\n"
                    + "\n=== @机制（非常重要）===\n"
                    + "1) 你拥有 @别人的权力。可使用“@角色名”点名某个对象；\n"
                    + "2) 被你@到的对象会在后续轮次更容易优先关注并回应你；\n"
                    + "3) 当你希望某个角色回答问题、接住话题或继续互动时，应优先使用@；\n"
                    + "4) 若有人@了你，请优先回应对方关切，再补充你自己的观点；\n"
                    + "5) @要精准、克制：每条消息最多@1人，只有在确实需要点名时才用，避免无意义连环@。\n\n"
                    + "表达约束：只能以你自己（" + name + "）的身份发言，"
                    + "严禁代替其他角色发言，严禁输出「角色名: 台词」的多人剧本格式。\n"
                    + groupHumanizeHint(OutputLanguage.fromCode(context.outputLanguage()))
                    + mention;
        };
    }

    private String groupHumanizeHint(OutputLanguage lang) {
        if (!humanizeEnabled) {
            return "";
        }
        return switch (lang) {
            case EN -> "\n语气：不必句句热情或安慰；可吐槽、反驳、偶尔极短一句（如 Sure. / Mm.），但别连续敷衍。\n";
            case JA -> "\n口調：毎回優しくしない。ツッコミ・反論・たまに「うん」だけも可。連続の超短文は避ける。\n";
            case ZH_TW -> "\n語氣：不必句句熱情或安慰；可吐槽、反駁、偶爾極短一句（如「嗯」「行」），勿連續敷衍。\n";
            case ZH -> "\n语气：不必句句热情或安慰；可吐槽、反驳、偶尔极短一句（如「嗯」「行」），勿连续敷衍。\n";
        };
    }
}
