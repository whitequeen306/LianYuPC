package com.lianyu.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.dao.entity.Message;
import org.junit.jupiter.api.Test;

class ConversationPreviewSnippetTest {

    @Test
    void voiceMessageShowsDurationInsteadOfTranscript() {
        Message voice = new Message();
        voice.setContent("你好，我是稻妻社奉行神里家神里绫华，初次见面，请多关照。");
        voice.setAudioUrl("pet/voice/ayaka/meet.wav");

        String snippet = ConversationService.formatConversationPreviewSnippet(voice);

        assertThat(snippet).startsWith("语音 ");
        assertThat(snippet).endsWith("″");
        assertThat(snippet).doesNotContain("神里绫华");
        assertThat(ConversationService.estimateVoicePreviewSeconds(voice.getContent())).isGreaterThan(1);
    }

    @Test
    void textMessageKeepsSnippet() {
        Message text = new Message();
        text.setContent("今晚月色真美。");
        text.setAudioUrl(null);

        assertThat(ConversationService.formatConversationPreviewSnippet(text)).isEqualTo("今晚月色真美。");
    }
}
