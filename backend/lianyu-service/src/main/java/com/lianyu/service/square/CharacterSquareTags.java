package com.lianyu.service.square;

import com.lianyu.common.i18n.OutputLanguage;
import java.util.List;

/**
 * 角色广场标签：仅「作品名」+「性格特点」，性格可跨作品筛选。
 */
final class CharacterSquareTags {

    private CharacterSquareTags() {
    }

    /** 作品 + 一条性格标签（常规角色） */
    static List<CharacterSquareCatalog.Tag> workAndPersonality(
            String lang, String workKey, String personalityKey) {
        return List.of(work(lang, workKey), personality(lang, personalityKey));
    }

    static boolean isKnownLabel(String label) {
        return KNOWN_ZH_LABELS.contains(label);
    }

    private static final java.util.Set<String> KNOWN_ZH_LABELS = java.util.Set.of(
            "原神", "约会大作战", "未来日记", "蔚蓝档案", "国家队", "路人女主",
            "邻座天使", "Re:零", "十日终焉", "弹丸论破",
            "温柔", "傲娇", "病娇", "活泼少女", "大姐姐", "自设"
    );

    /** 仅自设，无作品标签 */
    static List<CharacterSquareCatalog.Tag> ocOnly(String lang) {
        return List.of(personality(lang, "oc"));
    }

    static CharacterSquareCatalog.Tag work(String lang, String workKey) {
        String code = OutputLanguage.fromCode(lang).getCode();
        return switch (workKey) {
            case "genshin" -> tag("genshin", workLabel(code, "原神", "原神", "原神", "Genshin"));
            case "dal" -> tag("dal", workLabel(code, "约会大作战", "約會大作戰", "デート・ア・ライブ", "Date A Live"));
            case "mirai" -> tag("mirai", workLabel(code, "未来日记", "未來日記", "未来日記", "Future Diary"));
            case "bluearchive" -> tag("bluearchive", workLabel(code, "蔚蓝档案", "蔚藍檔案", "ブルアカ", "Blue Archive"));
            case "franxx" -> tag("franxx", workLabel(code, "国家队", "國家隊", "ダーリン・イン・ザ・フランキス", "Darling in the Franxx"));
            case "saekano" -> tag("saekano", workLabel(code, "路人女主", "路人女主", "冴えない彼女", "Saekano"));
            case "otonari" -> tag("otonari", workLabel(code, "邻座天使", "鄰座天使", "お隣の天使様", "Angel Next Door"));
            case "rezero" -> tag("rezero", workLabel(code, "Re:零", "Re:零", "Re:ゼロ", "Re:Zero"));
            case "shizhong" -> tag("shizhong", workLabel(code, "十日终焉", "十日終焉", "十日終焉", "Ten Days to Die"));
            case "danganronpa" -> tag("danganronpa", workLabel(code, "弹丸论破", "彈丸論破", "ダンガンロンパ", "Danganronpa"));
            default -> tag(workKey, workKey);
        };
    }

    static CharacterSquareCatalog.Tag personality(String lang, String personalityKey) {
        String code = OutputLanguage.fromCode(lang).getCode();
        return switch (personalityKey) {
            case "gentle" -> tag("gentle", personalityLabel(code, "温柔", "溫柔", "やさしい", "Gentle"));
            case "tsundere" -> tag("tsundere", personalityLabel(code, "傲娇", "傲嬌", "ツンデレ", "Tsundere"));
            case "yandere" -> tag("yandere", personalityLabel(code, "病娇", "病嬌", "ヤンデレ", "Yandere"));
            case "genki" -> tag("genki", personalityLabel(code, "活泼少女", "活潑少女", "元気少女", "Energetic"));
            case "onesan" -> tag("onesan", personalityLabel(code, "大姐姐", "大姐姐", "お姉さん", "Big sister"));
            case "oc" -> tag("oc", personalityLabel(code, "自设", "自設", "オリジナル", "OC"));
            default -> tag(personalityKey, personalityKey);
        };
    }

    private static String workLabel(String code, String zh, String zht, String ja, String en) {
        return labelFor(code, zh, zht, ja, en);
    }

    private static String personalityLabel(String code, String zh, String zht, String ja, String en) {
        return labelFor(code, zh, zht, ja, en);
    }

    private static String labelFor(String code, String zh, String zht, String ja, String en) {
        return switch (code) {
            case "zh-TW" -> zht;
            case "ja" -> ja;
            case "en" -> en;
            default -> zh;
        };
    }

    private static CharacterSquareCatalog.Tag tag(String key, String label) {
        return new CharacterSquareCatalog.Tag(key, label);
    }
}
