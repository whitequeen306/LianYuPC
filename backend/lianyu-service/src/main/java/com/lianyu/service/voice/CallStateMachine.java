package com.lianyu.service.voice;

/**
 * 语音通话的显式状态机（纯逻辑，无 IO）。
 *
 * <p>为什么需要它：原实现用 {@code turnBusy} / {@code bargeInAllowed} / {@code pendingUserText}
 * 三个散落标志控制麦克风，导致三处真实缺陷：
 *
 * <ul>
 *   <li>朗读期直接丢帧，打断前约 1 秒语音从未进过 ASR；</li>
 *   <li>「思考中」完全不能插话（客户端只在 speaking 才 arm 打断）；</li>
 *   <li>各层对「现在能不能说话」的判断口径不一致。</li>
 * </ul>
 *
 * <p>这里把「状态」收成唯一事实来源：会话层只问两个问题 ——
 * 麦克风要不要转发给 ASR（{@link #micForwarded()}）、现在能不能打断（{@link #canBargeIn()}）。
 * 客户端只做展示与采集，不再自行推导。
 *
 * <p>状态迁移（与 docs 中的表一致）：
 *
 * <pre>
 *   LISTENING --vad.start/asr.partial--> USER_SPEAKING --vad.end/endpoint--> THINKING
 *   THINKING  --first audio-------------> SPEAKING
 *   THINKING  --vad.start---------------> USER_SPEAKING（用户插话，调用方取消在途回合）
 *   SPEAKING  --barge_in----------------> INTERRUPTED --turn.cancelled--> LISTENING
 *   SPEAKING  --play.drained------------> LISTENING
 *   LISTENING --idle.trigger------------> THINKING（冷场主动，P2）
 * </pre>
 */
public final class CallStateMachine {

    public enum State {
        LISTENING,
        USER_SPEAKING,
        THINKING,
        SPEAKING,
        BACKCHANNEL,
        INTERRUPTED,
        CLOSED
    }

    public enum Event {
        START,
        OPENING_BEGIN,
        VAD_START,
        VAD_END,
        ASR_PARTIAL,
        ENDPOINT,
        FIRST_AUDIO,
        BARGE_IN,
        TURN_CANCELLED,
        PLAY_DRAINED,
        IDLE_TRIGGER,
        BACKCHANNEL_START,
        BACKCHANNEL_END,
        CLOSE
    }

    /**
     * 一次状态迁移的结果。
     *
     * @param changed 状态是否真的变了（没变时 {@code seq} 不递增，也不该下发事件）
     * @param seq     单调递增序号，供客户端丢弃乱序/重复的 {@code call.state}
     */
    public record Transition(boolean changed, long seq, State from, State to, String reason) {
    }

    private State state = State.LISTENING;
    private State backchannelReturn = State.LISTENING;
    private long seq = 0L;

    public synchronized State state() {
        return state;
    }

    public synchronized long seq() {
        return seq;
    }

    /**
     * 麦克风是否应转发给 ASR。
     *
     * <p>{@code SPEAKING / BACKCHANNEL} 不转发：外放时喇叭里的声音会被当成用户说话。
     * 打断所需的 pre-roll 由客户端环形缓冲补发，不依赖服务端保留音频。
     */
    public synchronized boolean micForwarded() {
        return switch (state) {
            case LISTENING, USER_SPEAKING, THINKING, INTERRUPTED -> true;
            case SPEAKING, BACKCHANNEL, CLOSED -> false;
        };
    }

    /** 当前是否允许打断。思考中与朗读中都允许；等用户真正开口后由会话层取消在途回合。 */
    public synchronized boolean canBargeIn() {
        return state == State.THINKING || state == State.SPEAKING;
    }

    public synchronized boolean isSpeaking() {
        return state == State.SPEAKING || state == State.BACKCHANNEL;
    }

    /**
     * 喂一个事件。
     *
     * @param reason 只用于日志与 {@code call.state.reason}，不参与迁移判定
     */
    public synchronized Transition on(Event event, String reason) {
        State from = state;
        State to = next(from, event);
        if (to == from) {
            return new Transition(false, seq, from, from, reason);
        }
        state = to;
        seq++;
        return new Transition(true, seq, from, to, reason);
    }

    private State next(State from, Event event) {
        if (event == Event.CLOSE) {
            return State.CLOSED;
        }
        if (from == State.CLOSED) {
            return State.CLOSED;
        }
        return switch (event) {
            case START -> State.LISTENING;
            case OPENING_BEGIN -> from == State.LISTENING ? State.THINKING : from;
            case VAD_START -> (from == State.LISTENING || from == State.THINKING
                    || from == State.BACKCHANNEL) ? State.USER_SPEAKING : from;
            case ASR_PARTIAL -> from == State.LISTENING ? State.USER_SPEAKING : from;
            case VAD_END, ENDPOINT -> from == State.USER_SPEAKING ? State.THINKING : from;
            case FIRST_AUDIO -> (from == State.LISTENING || from == State.THINKING
                    || from == State.USER_SPEAKING) ? State.SPEAKING : from;
            case BARGE_IN -> (from == State.THINKING || from == State.SPEAKING
                    || from == State.BACKCHANNEL) ? State.INTERRUPTED : from;
            case TURN_CANCELLED -> (from == State.INTERRUPTED || from == State.THINKING)
                    ? State.LISTENING : from;
            case PLAY_DRAINED -> from == State.SPEAKING ? State.LISTENING : from;
            case IDLE_TRIGGER -> from == State.LISTENING ? State.THINKING : from;
            case BACKCHANNEL_START -> {
                if (from == State.LISTENING || from == State.USER_SPEAKING) {
                    backchannelReturn = from;
                    yield State.BACKCHANNEL;
                }
                yield from;
            }
            case BACKCHANNEL_END -> from == State.BACKCHANNEL ? backchannelReturn : from;
            case CLOSE -> State.CLOSED;
        };
    }
}
