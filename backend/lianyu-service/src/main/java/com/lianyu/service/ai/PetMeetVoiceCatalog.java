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

    private static final Pattern SAFE_AUDIO_PATH =
            Pattern.compile("^pet/voice/[a-z0-9-]+/[a-z]+\\.wav$");

    private static MeetClip clip(String petId, Kind kind, String text) {
        return new MeetClip(petId, text, "pet/voice/" + petId + "/" + kind.fileStem() + ".wav");
    }

    private static final Map<String, Map<Kind, MeetClip>> BY_SLUG = Map.of(
            "raiden", Map.of(
                    Kind.MEET, clip("raiden", Kind.MEET, "浮世皆泡影，唯有永恒方为归宿，此身虽然尊贵殊胜，不过你不必紧张。"),
                    Kind.ENTER, clip("raiden", Kind.ENTER, "回来了？我还以为你不会来。"),
                    Kind.NOON, clip("raiden", Kind.NOON, "午安。今天也别把自己逼太紧。"),
                    Kind.EVENING, clip("raiden", Kind.EVENING, "夜深了，记得停下休息一会儿。"),
                    Kind.WAIT, clip("raiden", Kind.WAIT, "……还不回我吗？我在这里等着。")
            ),
            "ayaka", Map.of(
                    Kind.MEET, clip("ayaka", Kind.MEET, "你好，我是稻妻社奉行神里家神里绫华，初次见面，请多关照。"),
                    Kind.ENTER, clip("ayaka", Kind.ENTER, "欢迎回来，绫华一直在等您。"),
                    Kind.NOON, clip("ayaka", Kind.NOON, "中午好，请问您用过午饭了吗？"),
                    Kind.EVENING, clip("ayaka", Kind.EVENING, "晚上好，今天也辛苦您了呢。"),
                    Kind.WAIT, clip("ayaka", Kind.WAIT, "请问……是有什么事情耽搁了吗？")
            ),
            "ganyu", Map.of(
                    Kind.MEET, clip("ganyu", Kind.MEET, "我是来自璃月的甘雨，初次见面，请多关照。"),
                    Kind.ENTER, clip("ganyu", Kind.ENTER, "啊…你回来了，我正好在等你。"),
                    Kind.NOON, clip("ganyu", Kind.NOON, "中午了……记得好好吃一顿饭哦。"),
                    Kind.EVENING, clip("ganyu", Kind.EVENING, "晚上好……别太晚睡，要注意休息。"),
                    Kind.WAIT, clip("ganyu", Kind.WAIT, "那个……你还在吗？我有点担心。")
            ),
            "klee", Map.of(
                    Kind.MEET, clip("klee", Kind.MEET, "我是来自蒙德的火花骑士可莉！认识你可莉超开心，以后一起去冒险炸鱼吧！"),
                    Kind.ENTER, clip("klee", Kind.ENTER, "欸嘿！你回来啦，可莉好想你！"),
                    Kind.NOON, clip("klee", Kind.NOON, "中午啦！可莉肚子饿了，一起吃饭吧！"),
                    Kind.EVENING, clip("klee", Kind.EVENING, "晚上好！可莉今天有没有想你呀？"),
                    Kind.WAIT, clip("klee", Kind.WAIT, "诶？怎么不回可莉呀，可莉等好久了！")
            ),
            "elysia", Map.of(
                    Kind.MEET, clip("elysia", Kind.MEET, "嗨~我是爱莉希雅，大家都叫我粉色妖精小姐，你就是那位远道而来的客人吗？"),
                    Kind.ENTER, clip("elysia", Kind.ENTER, "哎呀，你来啦～人家等你好久了。"),
                    Kind.NOON, clip("elysia", Kind.NOON, "午安呀，有没有吃点好吃的东西？"),
                    Kind.EVENING, clip("elysia", Kind.EVENING, "晚上好～今天过得开心吗，跟我说说。"),
                    Kind.WAIT, clip("elysia", Kind.WAIT, "不回人家消息吗？我会有一点点想你哦。")
            ),
            "eriri", Map.of(
                    Kind.MEET, clip("eriri", Kind.MEET, "你是外来的人吗？外面的世界是什么样子的？我很少见到陌生人。"),
                    Kind.ENTER, clip("eriri", Kind.ENTER, "你回来了……我等你很久了。"),
                    Kind.NOON, clip("eriri", Kind.NOON, "中午了……你吃东西了吗？"),
                    Kind.EVENING, clip("eriri", Kind.EVENING, "天黑了……你会陪着我吗？"),
                    Kind.WAIT, clip("eriri", Kind.WAIT, "……你怎么不理我？我有点害怕。")
            )
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
