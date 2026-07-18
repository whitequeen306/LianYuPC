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
