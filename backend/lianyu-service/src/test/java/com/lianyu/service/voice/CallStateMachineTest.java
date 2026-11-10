package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.service.voice.CallStateMachine.Event;
import com.lianyu.service.voice.CallStateMachine.State;
import com.lianyu.service.voice.CallStateMachine.Transition;
import org.junit.jupiter.api.Test;

/**
 * 通话状态机的契约测试。
 *
 * <p>重点不是「能迁移」，而是三条线上体感对应的不变量：
 *
 * <ul>
 *   <li>朗读期麦克风不转发（防回声）；思考期必须转发（能插话）；</li>
 *   <li>打断必须经 INTERRUPTED 再回 LISTENING，客户端才有「被接住」的展示点；</li>
 *   <li>没发生迁移时 seq 不递增，避免客户端收到无意义的重复状态。</li>
 * </ul>
 */
class CallStateMachineTest {

    private static Transition go(CallStateMachine machine, Event event) {
        return machine.on(event, event.name().toLowerCase());
    }

    @Test
    void startsListeningAndForwardsMic() {
        CallStateMachine machine = new CallStateMachine();
        assertEquals(State.LISTENING, machine.state());
        assertTrue(machine.micForwarded());
        assertFalse(machine.canBargeIn());
    }

    @Test
    void happyPathReachesSpeakingThenDrainsBackToListening() {
        CallStateMachine machine = new CallStateMachine();
        go(machine, Event.START);
        go(machine, Event.VAD_START);
        assertEquals(State.USER_SPEAKING, machine.state());
        assertTrue(machine.micForwarded());

        go(machine, Event.VAD_END);
        assertEquals(State.THINKING, machine.state());
        assertTrue(machine.micForwarded());

        go(machine, Event.FIRST_AUDIO);
        assertEquals(State.SPEAKING, machine.state());
        assertFalse(machine.micForwarded(), "朗读期不能把喇叭声当用户输入");

        go(machine, Event.PLAY_DRAINED);
        assertEquals(State.LISTENING, machine.state());
        assertTrue(machine.micForwarded());
    }

    @Test
    void bargeInGoesThroughInterruptedBeforeListening() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.VAD_START, "start");
        machine.on(Event.VAD_END, "end");
        machine.on(Event.FIRST_AUDIO, "audio");

        Transition barge = machine.on(Event.BARGE_IN, "barge_in");
        assertTrue(barge.changed());
        assertEquals(State.INTERRUPTED, machine.state());
        assertTrue(machine.micForwarded(), "打断期间必须放行 pre-roll 帧");

        machine.on(Event.TURN_CANCELLED, "cancelled");
        assertEquals(State.LISTENING, machine.state());
    }

    @Test
    void userCanInterruptWhileThinking() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.VAD_START, "start");
        machine.on(Event.VAD_END, "end");
        assertEquals(State.THINKING, machine.state());

        machine.on(Event.VAD_START, "interrupt");
        assertEquals(State.USER_SPEAKING, machine.state(), "思考期说话应能直接进入用户回合");
    }

    @Test
    void speakingKeepsStateOnVadStartUntilClientDecidesBarge() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.VAD_START, "start");
        machine.on(Event.VAD_END, "end");
        machine.on(Event.FIRST_AUDIO, "audio");

        machine.on(Event.VAD_START, "maybe");
        assertEquals(State.SPEAKING, machine.state(), "仅检测到人声不应立刻打断（可能是回声）");
        assertFalse(machine.micForwarded());
    }

    @Test
    void asrPartialOnlyPromotesFromListening() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.ASR_PARTIAL, "partial");
        assertEquals(State.USER_SPEAKING, machine.state());

        machine.on(Event.VAD_END, "end");
        machine.on(Event.FIRST_AUDIO, "audio");
        machine.on(Event.ASR_PARTIAL, "late-partial");
        assertEquals(State.SPEAKING, machine.state(), "朗读期的迟到 partial 不应把状态拉回用户说话");
    }

    @Test
    void idleTriggerOnlyFromListening() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.IDLE_TRIGGER, "idle");
        assertEquals(State.THINKING, machine.state());

        machine.on(Event.TURN_CANCELLED, "cue-failed");
        assertEquals(State.LISTENING, machine.state());

        machine.on(Event.VAD_START, "user");
        machine.on(Event.IDLE_TRIGGER, "idle-while-user");
        assertEquals(State.USER_SPEAKING, machine.state());
    }

    @Test
    void backchannelReturnsToPreviousState() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.BACKCHANNEL_START, "ack");
        assertEquals(State.BACKCHANNEL, machine.state());
        assertFalse(machine.micForwarded());
        machine.on(Event.BACKCHANNEL_END, "ack-done");
        assertEquals(State.LISTENING, machine.state());

        machine.on(Event.VAD_START, "user");
        machine.on(Event.BACKCHANNEL_START, "ack2");
        machine.on(Event.BACKCHANNEL_END, "ack2-done");
        assertEquals(State.USER_SPEAKING, machine.state());
    }

    @Test
    void seqOnlyAdvancesOnRealTransitions() {
        CallStateMachine machine = new CallStateMachine();
        long before = machine.seq();

        Transition noop = machine.on(Event.START, "already-listening");
        assertFalse(noop.changed());
        assertEquals(before, machine.seq());

        Transition moved = machine.on(Event.VAD_START, "user");
        assertTrue(moved.changed());
        assertEquals(before + 1, machine.seq());
        assertEquals(before + 1, moved.seq());
    }

    @Test
    void closeIsSticky() {
        CallStateMachine machine = new CallStateMachine();
        machine.on(Event.VAD_START, "start");
        machine.on(Event.CLOSE, "hangup");
        assertEquals(State.CLOSED, machine.state());
        assertFalse(machine.micForwarded());

        machine.on(Event.VAD_START, "late");
        assertEquals(State.CLOSED, machine.state());
    }
}
