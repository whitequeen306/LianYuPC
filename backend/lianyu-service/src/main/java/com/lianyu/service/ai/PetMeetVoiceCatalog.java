package com.lianyu.service.ai;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Fixed chat voice clips for square characters that share a desktop-pet VC id.
 * Audio files ship with the Electron client under {@code public/pet/voice/<petId>/<kind>.wav}.
 *
 * <p>Kinds: meet, enter, noon, evening, wait (nudge after unreplied proactives).
 * Meet lines are personality-shaped (length is not locked); other slots stay conversational.
 */
@Component
public class PetMeetVoiceCatalog {

    public enum Kind {
        MEET,
        ENTER,
        NOON,
        EVENING,
        WAIT;

        public String fileStem() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record MeetClip(String petId, String text, String audioPath) {
    }

    /** Pet ids may use underscore (e.g. erii_uesugi); hyphen still allowed. */
    private static final Pattern SAFE_AUDIO_PATH =
            Pattern.compile("^pet/voice/[a-z0-9_-]+/[a-z]+\\.wav$");

    private static MeetClip clip(String petId, Kind kind, String text) {
        return new MeetClip(petId, text, "pet/voice/" + petId + "/" + kind.fileStem() + ".wav");
    }

    private static Map<Kind, MeetClip> kinds(String petId,
                                             String meet, String enter, String noon,
                                             String evening, String wait) {
        return Map.of(
                Kind.MEET, clip(petId, Kind.MEET, meet),
                Kind.ENTER, clip(petId, Kind.ENTER, enter),
                Kind.NOON, clip(petId, Kind.NOON, noon),
                Kind.EVENING, clip(petId, Kind.EVENING, evening),
                Kind.WAIT, clip(petId, Kind.WAIT, wait)
        );
    }

    private static final Map<String, Map<Kind, MeetClip>> BY_SLUG = Map.ofEntries(
            Map.entry("raiden", kinds("raiden",
                    "浮世皆泡影，唯有永恒方为归宿，此身虽然尊贵殊胜，不过你不必紧张。",
                    "回来了？我还以为你不会来。",
                    "午安。今天也别把自己逼太紧。",
                    "夜深了，记得停下休息一会儿。",
                    "……还不回我吗？我在这里等着。")),
            Map.entry("ayaka", kinds("ayaka",
                    "你好，我是稻妻社奉行神里家神里绫华，初次见面，请多关照。",
                    "欢迎回来，绫华一直在等您。",
                    "中午好，请问您用过午饭了吗？",
                    "晚上好，今天也辛苦您了呢。",
                    "请问……是有什么事情耽搁了吗？")),
            Map.entry("ganyu", kinds("ganyu",
                    "我是来自璃月的甘雨，初次见面，请多关照。",
                    "啊…你回来了，我正好在等你。",
                    "中午了……记得好好吃一顿饭哦。",
                    "晚上好……别太晚睡，要注意休息。",
                    "那个……你还在吗？我有点担心。")),
            Map.entry("klee", kinds("klee",
                    "我是来自蒙德的火花骑士可莉！认识你可莉超开心，以后一起去冒险炸鱼吧！",
                    "欸嘿！你回来啦，可莉好想你！",
                    "中午啦！可莉肚子饿了，一起吃饭吧！",
                    "晚上好！可莉今天有没有想你呀？",
                    "诶？怎么不回可莉呀，可莉等好久了！")),
            Map.entry("elysia", kinds("elysia",
                    "嗨~我是爱莉希雅，大家都叫我粉色妖精小姐，你就是那位远道而来的客人吗？",
                    "哎呀，你来啦～人家等你好久了。",
                    "午安呀，有没有吃点好吃的东西？",
                    "晚上好～今天过得开心吗，跟我说说。",
                    "不回人家消息吗？我会有一点点想你哦。")),
            Map.entry("erii_uesugi", kinds("erii_uesugi",
                    "你是外来的人吗？外面的世界是什么样子的？我很少见到陌生人。",
                    "你回来了……我等你很久了。",
                    "中午了……你吃东西了吗？",
                    "天黑了……你会陪着我吗？",
                    "……你怎么不理我？我有点害怕。")),
            Map.entry("yae_miko", kinds("yae_miko",
                    "呵呵，旅行者，本宫是鸣神大社的八重神子。有趣的人总是会自己找上门来呢。",
                    "回来了？正好，本宫正缺一个可以聊聊的人。",
                    "午安。难得偷得半日闲，不如陪本宫喝杯茶？",
                    "夜深了。别总把自己忙得团团转，偶尔也该享受一点悠闲。",
                    "怎么不说话了？本宫可是很有耐心的……偶尔也会等得无聊哦。")),
            Map.entry("kokomi", kinds("kokomi",
                    "你好，我是海祇岛的现人神巫女珊瑚宫心海。初次见面，请多关照。",
                    "欢迎回来。能再次见到你，我很安心。",
                    "中午好。记得按时用餐，精力是一切计划的基础。",
                    "晚上好。今天的事务都顺利吗？如果累了，可以先休息。",
                    "还在忙吗？不着急，我会在这里等你。")),
            Map.entry("shenhe", kinds("shenhe",
                    "我是申鹤。师父让我下山历练……你，就是旅行者吗？",
                    "你回来了。我一直在这里。",
                    "中午了。你要吃东西吗？我……可以陪着。",
                    "夜深了。凡人需要休息，你也一样。",
                    "你怎么不说话？是我哪里说错了吗？")),
            Map.entry("nahida", kinds("nahida",
                    "你好呀，旅行者。我是纳西妲，也可以叫我小吉祥草王。很高兴认识你。",
                    "你回来啦。我刚刚还在想你会不会来呢。",
                    "中午好。阳光正好，要不要一起休息一会儿？",
                    "晚上好。今天学到什么有趣的事情了吗？跟我说说吧。",
                    "还在忙吗？没关系，我会轻轻等着你的。")),
            Map.entry("hu_tao", kinds("hu_tao",
                    "嘿嘿，旅行者！我是往生堂堂主胡桃，初次见面请多关照哟～",
                    "回来啦回来啦！堂主大人可是等你好久咯。",
                    "中午啦！吃饱了才有力气陪胡桃到处跑哦。",
                    "晚上好～月亮出来了，正适合讲一点小故事。",
                    "诶？怎么不理胡桃呀，胡桃可是很寂寞的哦～")),
            Map.entry("furina", kinds("furina",
                    "咳咳——本座是芙宁娜！枫丹的焦点、舞台的中心，请好好记住这个名字。",
                    "你来了？很好，观众席总算又热闹起来了。",
                    "午安。就算是本座，也需要一点休息时间的。",
                    "夜深了。今天的演出……唔，也算圆满吧。",
                    "怎么不说话？本座的登场可不是为了被晾在一边的！")),
            Map.entry("noelle", kinds("noelle",
                    "您好！我是西风骑士团的女仆诺艾尔。有任何需要帮忙的地方，请尽管吩咐。",
                    "欢迎回来。有我能帮上忙的事吗？",
                    "中午好。您用过午餐了吗？要注意按时吃饭哦。",
                    "晚上好。今天也辛苦了，请好好休息。",
                    "请问……是有什么事情耽搁了吗？我会在这里等您的。")),
            Map.entry("kurumi", kinds("kurumi",
                    "呵呵，初次见面呢。我是时崎狂三——请多指教哦，士道君。",
                    "回来了呀。我可是一直在等你哦。",
                    "午安。难得的闲暇，要不要陪狂三聊一会儿？",
                    "夜深了呢。今晚的月亮，很适合两人独处哦。",
                    "怎么不理人了？让狂三一个人等着……可是会寂寞的哟。"))
    );

    public MeetClip findBySlug(String slug) {
        return find(slug, Kind.MEET);
    }

    public MeetClip find(String slug, Kind kind) {
        if (slug == null || slug.isBlank() || kind == null) {
            return null;
        }
        Map<Kind, MeetClip> kinds = BY_SLUG.get(slug.trim().toLowerCase(Locale.ROOT));
        if (kinds == null) {
            return null;
        }
        return kinds.get(kind);
    }

    public boolean hasVoice(String slug) {
        return slug != null && !slug.isBlank()
                && BY_SLUG.containsKey(slug.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isSafeClientAudioPath(String path) {
        return path != null && SAFE_AUDIO_PATH.matcher(path).matches();
    }
}
