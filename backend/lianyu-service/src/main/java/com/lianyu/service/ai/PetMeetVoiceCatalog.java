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
 * Every line is intentionally longer than 10 Chinese characters for natural TTS pacing.
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
                    Kind.MEET, clip("raiden", Kind.MEET, "……你终于来了，我已等候多时。"),
                    Kind.ENTER, clip("raiden", Kind.ENTER, "回来了？我还以为你不会来。"),
                    Kind.NOON, clip("raiden", Kind.NOON, "午安。今天也别把自己逼太紧。"),
                    Kind.EVENING, clip("raiden", Kind.EVENING, "夜深了，记得停下休息一会儿。"),
                    Kind.WAIT, clip("raiden", Kind.WAIT, "……还不回我吗？我在这里等着。")
            ),
            "ayaka", Map.of(
                    Kind.MEET, clip("ayaka", Kind.MEET, "初次见面，还请您多多关照呐。"),
                    Kind.ENTER, clip("ayaka", Kind.ENTER, "欢迎回来，绫华一直在等您。"),
                    Kind.NOON, clip("ayaka", Kind.NOON, "中午好，请问您用过午饭了吗？"),
                    Kind.EVENING, clip("ayaka", Kind.EVENING, "晚上好，今天也辛苦您了呢。"),
                    Kind.WAIT, clip("ayaka", Kind.WAIT, "请问……是有什么事情耽搁了吗？")
            ),
            "ganyu", Map.of(
                    Kind.MEET, clip("ganyu", Kind.MEET, "你好……我是甘雨，请多关照。"),
                    Kind.ENTER, clip("ganyu", Kind.ENTER, "啊…你回来了，我正好在等你。"),
                    Kind.NOON, clip("ganyu", Kind.NOON, "中午了……记得好好吃一顿饭哦。"),
                    Kind.EVENING, clip("ganyu", Kind.EVENING, "晚上好……别太晚睡，要注意休息。"),
                    Kind.WAIT, clip("ganyu", Kind.WAIT, "那个……你还在吗？我有点担心。")
            ),
            "klee", Map.of(
                    Kind.MEET, clip("klee", Kind.MEET, "哇！新朋友！可莉是火花骑士可莉！"),
                    Kind.ENTER, clip("klee", Kind.ENTER, "欸嘿！你回来啦，可莉好想你！"),
                    Kind.NOON, clip("klee", Kind.NOON, "中午啦！可莉肚子饿了，一起吃饭吧！"),
                    Kind.EVENING, clip("klee", Kind.EVENING, "晚上好！可莉今天有没有想你呀？"),
                    Kind.WAIT, clip("klee", Kind.WAIT, "诶？怎么不回可莉呀，可莉等好久了！")
            ),
            "elysia", Map.of(
                    Kind.MEET, clip("elysia", Kind.MEET, "嗨～很高兴遇见你，要好好相处哦。"),
                    Kind.ENTER, clip("elysia", Kind.ENTER, "哎呀，你来啦～人家等你好久了。"),
                    Kind.NOON, clip("elysia", Kind.NOON, "午安呀，有没有吃点好吃的东西？"),
                    Kind.EVENING, clip("elysia", Kind.EVENING, "晚上好～今天过得开心吗，跟我说说。"),
                    Kind.WAIT, clip("elysia", Kind.WAIT, "不回人家消息吗？我会有一点点想你哦。")
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
