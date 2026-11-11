package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.ai.AsrSession;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.conversation.VoiceCallService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * 双工会话的状态机接入与打断链路。
 *
 * <p>这些用例保护的是四条线上体感：
 * <ul>
 *   <li>朗读期麦克风帧不进 ASR（防回声），思考期/打断后必须进；</li>
 *   <li>打断会取消在途回合、清掉去重基线，打断前那半句能重新进 ASR；</li>
 *   <li>play.drained 把 SPEAKING 收回 LISTENING，麦克风重新放行；</li>
 *   <li>同一句话被打断后再说一遍，不能被「复读机」去重误挡。</li>
 * </ul>
 *
 * <p>用本地音色目标（{@code CUSTOM_LOCAL}）跑完整回合：这条路径不启动 TTS 线程，
 * 回合是同步收尾的，测试不必等真实音频。
 */
class VoiceCallDuplexSessionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // 回合线程与测试线程会并发读写，普通 List 在 await 里迭代会 ConcurrentModificationException
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final VoiceEventSink sink = new VoiceEventSink() {
        @Override
        public void sendText(String json) {
            sent.add(json);
        }
    };

    private AsrStreamClient.Listener asrListener;
    private AsrSession asrSession;
    private VoiceCallService service;

    private VoiceCallDuplexSession newSession() {
        return newSession(VoiceCallOptions.defaults());
    }

    private VoiceCallDuplexSession newSession(VoiceCallOptions options) {
        asrSession = mock(AsrSession.class);
        AsrStreamClient asrClient = mock(AsrStreamClient.class);
        when(asrClient.open(any())).thenAnswer(inv -> {
            asrListener = inv.getArgument(0);
            return asrSession;
        });
        service = mock(VoiceCallService.class);
        when(service.resolveAndAssertCallTarget(anyLong(), anyLong()))
                .thenReturn(new VoiceCallTarget(
                        VoiceCallTarget.Mode.CUSTOM_LOCAL,
                        null, null, null, null, null, null,
                        "http://127.0.0.1:9880", null, null));
        when(service.clampReply(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(service.streamVoiceReply(anyLong(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("好呀，我在呢。"));
        return new VoiceCallDuplexSession(
                sink, objectMapper, 1L, 2L, service, asrClient,
                mock(VoiceSynthesisService.class), options);
    }

    /** 官方音色目标（有 pet 固定语音库），用于 clip / 开场用例。 */
    private VoiceCallDuplexSession newPetSession(VoiceCallOptions options) {
        asrSession = mock(AsrSession.class);
        AsrStreamClient asrClient = mock(AsrStreamClient.class);
        when(asrClient.open(any())).thenAnswer(inv -> {
            asrListener = inv.getArgument(0);
            return asrSession;
        });
        service = mock(VoiceCallService.class);
        when(service.resolveAndAssertCallTarget(anyLong(), anyLong()))
                .thenReturn(new VoiceCallTarget(
                        VoiceCallTarget.Mode.OFFICIAL_PET,
                        "ganyu", "voice-1", "voice-1", "key",
                        null, null, null, "model-http", "model-realtime"));
        when(service.clampReply(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(service.generateVoiceCue(anyLong(), anyLong(), anyString()))
                .thenReturn("我刚回来，你等很久了吗？");
        // 非本地音色走服务端 TTS：给一小段 PCM，让管线完整收尾
        org.mockito.Mockito.doAnswer(inv -> {
            com.lianyu.service.agent.TtsPort.TtsChunkSink chunkSink = inv.getArgument(3);
            chunkSink.onChunk(new byte[] {1, 2, 3, 4});
            chunkSink.onDone();
            return null;
        }).when(service).streamSynthesizeForTarget(any(), anyString(), any(VoiceProsody.class), any());
        return new VoiceCallDuplexSession(
                sink, objectMapper, 1L, 2L, service, asrClient,
                mock(VoiceSynthesisService.class), options);
    }

    /** 跑完一个完整回合：ASR 文本 → 端点 → 本地音色回合并进入 SPEAKING。 */
    private void completeTurn(VoiceCallDuplexSession session, String text) {
        asrListener.onPartial(text);
        asrListener.onEndpoint();
        // tts.local 在 FIRST_AUDIO 之后发出：收到它就说明状态机已经到 SPEAKING
        await(() -> sent.stream().anyMatch(m -> m.contains("\"type\":\"tts.local\"")));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("等待条件超时");
    }

    @Test
    void speakingDropsMicFramesAndDrainRestoresThem() {
        VoiceCallDuplexSession session = newSession();
        completeTurn(session, "今天好累呀");

        clearInvocations(asrSession);
        session.onPcm(new byte[] {1, 2, 3, 4});
        verify(asrSession, never()).sendPcm(any());

        session.onPlayDrained(7);
        session.onPcm(new byte[] {5, 6, 7, 8});
        verify(asrSession, times(1)).sendPcm(any());
    }

    @Test
    void thinkingStateStillForwardsMicForInterruption() {
        VoiceCallDuplexSession session = newSession();
        session.onVad("start");
        session.onVad("end");

        clearInvocations(asrSession);
        session.onPcm(new byte[] {9, 9});
        verify(asrSession, times(1)).sendPcm(any());
    }

    @Test
    void bargeInClearsDedupeSoTheSameWordsCanBeHeardAgain() {
        VoiceCallDuplexSession session = newSession();
        completeTurn(session, "你先别说");

        clearInvocations(asrSession);
        session.bargeIn(1500);
        assertTrue(sent.stream().anyMatch(m -> m.contains("\"type\":\"turn.cancelled\"")));

        // 打断后麦克风立刻放行：pre-roll 回灌帧走的就是这条路
        session.onPcm(new byte[] {1});
        session.onPcm(new byte[] {2});
        verify(asrSession, times(2)).sendPcm(any());

        // 去重基线被清：同一句话再说一遍必须能开新回合（不清会被当重复丢掉）
        long before = sent.stream().filter(m -> m.contains("\"type\":\"turn.start\"")).count();
        asrListener.onPartial("你先别说");
        asrListener.onEndpoint();
        await(() -> sent.stream().filter(m -> m.contains("\"type\":\"turn.start\"")).count() > before);
    }

    @Test
    void playDrainedEmitsListeningState() {
        VoiceCallDuplexSession session = newSession();
        completeTurn(session, "陪我聊会儿");
        sent.clear();

        session.onPlayDrained(3);
        await(() -> sent.stream().anyMatch(
                m -> m.contains("\"type\":\"call.state\"") && m.contains("\"LISTENING\"")));
    }

    @Test
    void openingSendsPickupClipAndSpeaksTheCueOnce() {
        VoiceCallDuplexSession session = newPetSession(new VoiceCallOptions(false, true, true));
        session.startOpening();

        await(() -> sent.stream().anyMatch(
                m -> m.contains("\"type\":\"tts.clip\"") && m.contains("\"pickup\"")));
        await(() -> sent.stream().anyMatch(
                m -> m.contains("\"type\":\"turn.done\"") && m.contains("\"opening\"")));

        // 开场白必须落库，下一轮历史才接得上
        org.mockito.Mockito.verify(service).persistAssistantTurn(1L, 2L, "我刚回来，你等很久了吗？");
        // pickup clip 必须早于开场白的 TTS 音频
        int clipAt = indexOf("\"type\":\"tts.clip\"");
        int turnAt = indexOf("\"type\":\"turn.start\"");
        assertTrue(clipAt >= 0 && turnAt > clipAt, "pickup 必须先出声");
    }

    @Test
    void idleNudgeSpeaksOnceWhenTheCallFallsSilent() {
        // 静音 250ms 就触发；间隔设成 60s，保证这一通只会主动这一次（限频逻辑另有保护）
        VoiceCallDuplexSession session = newPetSession(
                new VoiceCallOptions(false, true, false, true, false, 250, 60_000));

        await(() -> sent.stream().anyMatch(
                m -> m.contains("\"type\":\"turn.done\"") && m.contains("\"idle\"")));

        org.mockito.Mockito.verify(service, times(1)).generateVoiceCue(1L, 2L, "idle");
        org.mockito.Mockito.verify(service).persistAssistantTurn(1L, 2L, "我刚回来，你等很久了吗？");
    }

    @Test
    void idleNudgeIsCancelledWhenUserSpeaksFirst() {
        VoiceCallDuplexSession session = newPetSession(
                new VoiceCallOptions(false, true, false, true, false, 100, 60_000));
        // 让 idle 台词生成得慢一点，好在它返回前插一句「用户开口」
        when(service.generateVoiceCue(anyLong(), anyLong(), anyString())).thenAnswer(inv -> {
            Thread.sleep(600);
            return "在吗？";
        });

        // 等到状态机进入 THINKING（说明 idle 已经触发、正在生成）
        await(() -> sent.stream().anyMatch(
                m -> m.contains("\"type\":\"call.state\"") && m.contains("\"THINKING\"")));
        session.onVad("start");
        await(() -> sent.stream().anyMatch(m -> m.contains("\"turn.cancelled\"")));

        sleep(800);
        assertTrue(sent.stream().noneMatch(
                m -> m.contains("\"type\":\"turn.done\"") && m.contains("\"idle\"")),
                "用户先开口时冷场台词必须作废");
        org.mockito.Mockito.verify(service, never()).persistAssistantTurn(anyLong(), anyLong(), anyString());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int indexOf(String needle) {
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
