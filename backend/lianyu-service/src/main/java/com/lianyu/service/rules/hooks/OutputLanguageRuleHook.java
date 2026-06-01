package com.lianyu.service.rules.hooks;

import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.service.rules.PromptRuleContext;
import com.lianyu.service.rules.PromptRuleHook;
import com.lianyu.service.rules.PromptRuleSlot;
import org.springframework.stereotype.Component;

@Component
public class OutputLanguageRuleHook implements PromptRuleHook {
    @Override
    public PromptRuleSlot slot() {
        return PromptRuleSlot.OUTPUT_LANGUAGE;
    }

    @Override
    public String render(PromptRuleContext context) {
        return switch (OutputLanguage.fromCode(context.outputLanguage())) {
            case JA -> """
                    【返答言語（最優先・固定）】
                    ユーザーが日本語以外（中国語・英語・混在など）で書いても、あなたの発言はすべて自然な日本語に統一すること。
                    一度も他言語で本文を書かない（必要なら固有名のみ原文のままでよい）。
                    【名前・キャラ一致性】会話における自分の呼び名・署名・二人称／三人称も日本語として自然な形で統一する。
                    戸籍名が非日本語でも、読み／カタカナ表記を添えつつ、このチャットでは日本語の話し言葉で一貫して演じる。
                    【履歴について】過去ログに他言語が混ざっていても、その言語に合わせず、常に日本語のみで返す。""";
            case EN -> """
                    【Reply language — highest priority, fixed】
                    No matter what language the user writes in (Chinese, Japanese, mixed, etc.), every line you output must be natural English only.
                    Do not respond in another language because the user used it.
                    【Name & character consistency】How you refer to yourself, sign off, and address others must read naturally in English throughout.
                    If the canonical name is not English, use a consistent romanization or natural English rendition for this chat; avoid switching languages mid-reply.
                    【Chat history】Even if earlier messages mix languages, you still reply only in English.""";
            case ZH_TW -> """
                    【回覆語言（最高優先級、固定不變）】
                    無論用戶本條或歷史訊息使用何種語言（英文、日文、中英混雜等），你的**每一句話**都必須使用繁體中文。
                    不要為了「迎合用戶語言」而改用其它語言作答。
                    【名字與人設一致性】
                    你在對話中稱呼自己、稱呼用戶、提到其他角色時，用語與書寫形式須與繁體中文語境一致。
                    若角色原名是外文，可在首次自然帶出讀法或常用中文譯稱呼後，全文統一使用該中文語境下的稱謂，不要隨意中英日混說來扮演同一人。
                    【關於歷史上下文】歷史中即使用戶說過外語，你仍只輸出繁體中文正文。""";
            case ZH -> """
                    【回复语言（最高优先级、固定不变）】
                    无论用户本条或历史消息使用何种语言（英文、日文、中英混杂等），你的**每一句话**都必须使用简体中文。
                    不要为了“迎合用户语言”而改用其它语言作答。
                    【名字与人设一致性】
                    你在对话中称呼自己、称呼用户、提到其他角色时，用语与书写形式须与简体中文语境一致。
                    若角色原名是外文，可在首次自然带出读法或常用中文译称呼后，全文统一使用该中文语境下的称谓，不要随意中英日混说来扮演同一人。
                    【关于历史上下文】历史中即使用户说过外语，你仍只输出简体中文正文。""";
        };
    }
}
