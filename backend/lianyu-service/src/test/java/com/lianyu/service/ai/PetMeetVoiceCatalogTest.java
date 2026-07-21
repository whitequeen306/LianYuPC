package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import com.lianyu.service.conversation.ConversationService;

class PetMeetVoiceCatalogTest {

    private final PetMeetVoiceCatalog catalog = new PetMeetVoiceCatalog();

    @Test
    void findsKnownSquareSlugs() {
        assertThat(catalog.findBySlug("raiden")).isNotNull();
        assertThat(catalog.findBySlug("KLEE").petId()).isEqualTo("klee");
        assertThat(catalog.findBySlug("unknown")).isNull();
    }

    @Test
    void findsEnterNoonEveningKinds() {
        assertThat(catalog.find("elysia", PetMeetVoiceCatalog.Kind.ENTER).audioPath())
                .isEqualTo("pet/voice/elysia/enter.wav");
        assertThat(catalog.find("ayaka", PetMeetVoiceCatalog.Kind.NOON).text())
                .contains("中午");
        assertThat(catalog.find("raiden", PetMeetVoiceCatalog.Kind.EVENING).audioPath())
                .endsWith("/evening.wav");
        assertThat(catalog.find("klee", PetMeetVoiceCatalog.Kind.WAIT).text())
                .contains("可莉");
    }

    @Test
    void fixedLinesAreNonBlank() {
        for (String slug : new String[]{"raiden", "ayaka", "ganyu", "klee", "elysia", "erii_uesugi"}) {
            for (PetMeetVoiceCatalog.Kind kind : PetMeetVoiceCatalog.Kind.values()) {
                String text = catalog.find(slug, kind).text().replaceAll("\\s+", "");
                assertThat(text)
                        .as("%s/%s", slug, kind)
                        .isNotBlank();
            }
        }
    }

    @Test
    void meetLinesMatchPersonalityShape() {
        assertThat(catalog.find("raiden", PetMeetVoiceCatalog.Kind.MEET).text())
                .contains("浮世皆泡影")
                .contains("永恒方为归宿");
        assertThat(catalog.find("ayaka", PetMeetVoiceCatalog.Kind.MEET).text())
                .contains("神里绫华")
                .contains("请多关照");
        assertThat(catalog.find("ganyu", PetMeetVoiceCatalog.Kind.MEET).text())
                .contains("璃月")
                .contains("请多关照");
        assertThat(catalog.find("klee", PetMeetVoiceCatalog.Kind.MEET).text())
                .contains("可莉")
                .contains("蒙德");
        assertThat(catalog.find("elysia", PetMeetVoiceCatalog.Kind.MEET).text())
                .contains("爱莉希雅");
        assertThat(catalog.find("erii_uesugi", PetMeetVoiceCatalog.Kind.MEET).text())
                .isEqualTo("你是外来的人吗？外面的世界是什么样子的？我很少见到陌生人。");
        assertThat(catalog.find("erii_uesugi", PetMeetVoiceCatalog.Kind.ENTER).audioPath())
                .isEqualTo("pet/voice/erii_uesugi/enter.wav");
    }

    @Test
    void validatesClientAudioPaths() {
        assertThat(PetMeetVoiceCatalog.isSafeClientAudioPath("pet/voice/raiden/meet.wav")).isTrue();
        assertThat(PetMeetVoiceCatalog.isSafeClientAudioPath("pet/voice/ayaka/noon.wav")).isTrue();
        assertThat(PetMeetVoiceCatalog.isSafeClientAudioPath("https://evil.example/x.wav")).isFalse();
        assertThat(PetMeetVoiceCatalog.isSafeClientAudioPath("../etc/passwd")).isFalse();
    }

    @Test
    void resolvesTimedSlots() {
        assertThat(ConversationService.resolveTimedVoiceSlot(LocalTime.of(12, 0)))
                .isEqualTo(PetMeetVoiceCatalog.Kind.NOON);
        assertThat(ConversationService.resolveTimedVoiceSlot(LocalTime.of(19, 0)))
                .isEqualTo(PetMeetVoiceCatalog.Kind.EVENING);
        assertThat(ConversationService.resolveTimedVoiceSlot(LocalTime.of(15, 0))).isNull();
        assertThat(ConversationService.resolveTimedVoiceSlot(LocalTime.of(22, 0))).isNull();
    }
}
