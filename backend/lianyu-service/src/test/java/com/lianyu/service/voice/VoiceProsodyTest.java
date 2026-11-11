package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 情绪 → 韵律的纯数值映射：只锁区间与方向，不锁具体小数（否则调参就红）。 */
class VoiceProsodyTest {

    @Test
    void unknownEmotionFallsBackToNeutral() {
        assertEquals(VoiceProsody.NEUTRAL, VoiceProsody.resolve(null, 80));
        assertEquals(VoiceProsody.NEUTRAL, VoiceProsody.resolve("", 80));
        assertEquals(VoiceProsody.NEUTRAL, VoiceProsody.resolve("平静", 80));
    }

    @Test
    void sadAndTiredSlowDownAndLengthenPauses() {
        VoiceProsody sad = VoiceProsody.resolve("难过", 80);
        assertTrue(sad.rate() < 1.0, "难过应当放慢");
        assertTrue(sad.pauseScale() > 1.0, "难过应当拉长停顿");
        assertTrue(sad.volumeDb() < 0, "难过应当轻一点");

        VoiceProsody tired = VoiceProsody.resolve("疲惫", 80);
        assertTrue(tired.rate() < 1.0);
        assertTrue(tired.pauseScale() > 1.0);
    }

    @Test
    void happyAndExcitedSpeedUpAndTightenPauses() {
        VoiceProsody happy = VoiceProsody.resolve("开心", 80);
        assertTrue(happy.rate() > 1.0);
        assertTrue(happy.pauseScale() < 1.0);

        VoiceProsody excited = VoiceProsody.resolve("兴奋", 80);
        assertTrue(excited.rate() >= happy.rate(), "更兴奋不该比开心更慢");
    }

    @Test
    void intensityScalesTheOffset() {
        VoiceProsody weak = VoiceProsody.resolve("难过", 10);
        VoiceProsody strong = VoiceProsody.resolve("难过", 100);
        assertTrue(strong.rate() < weak.rate(), "强度越高偏移越大");
        assertTrue(strong.pauseScale() > weak.pauseScale());
        // 低强度也不能完全等于中性：0.4 的保底让人听得出情绪
        assertTrue(weak.rate() < 1.0);
    }

    @Test
    void rateAndPauseStayWithinSafeBounds() {
        for (double intensity : new double[] {0, 30, 60, 100}) {
            for (String emotion : new String[] {"开心", "兴奋", "难过", "疲惫", "撒娇", "想念", "生气", "吃醋", "担心"}) {
                VoiceProsody p = VoiceProsody.resolve(emotion, intensity);
                assertTrue(p.rate() >= 0.85 && p.rate() <= 1.12, emotion + " rate 越界: " + p.rate());
                assertTrue(p.pauseScale() >= 0.75 && p.pauseScale() <= 1.45, emotion + " pause 越界");
            }
        }
    }

    @Test
    void rateMirrorOnlyTouchesRate() {
        VoiceProsody sad = VoiceProsody.resolve("难过", 80);
        VoiceProsody faster = sad.withRateFactor(1.05);

        assertTrue(faster.rate() > sad.rate(), "快语速应当把 rate 抬起来");
        assertEquals(sad.pauseScale(), faster.pauseScale(), "停顿属于情绪，不能被语速改掉");
        assertEquals(sad.pitch(), faster.pitch());
        assertEquals(sad.volumeDb(), faster.volumeDb());
    }

    @Test
    void rateMirrorKeepsNeutralUntouched() {
        assertEquals(VoiceProsody.NEUTRAL, VoiceProsody.NEUTRAL.withRateFactor(1.0));
        assertEquals(VoiceProsody.NEUTRAL, VoiceProsody.NEUTRAL.withRateFactor(Double.NaN));
    }

    @Test
    void rateMirrorClampsWhenStackedOnEmotion() {
        // 兴奋 already 拉满 1.12：再叠 1.05 不能无限往上跑
        VoiceProsody excited = VoiceProsody.resolve("兴奋", 100);
        assertTrue(excited.withRateFactor(1.05).rate() <= 1.20);
        VoiceProsody tired = VoiceProsody.resolve("疲惫", 100);
        assertTrue(tired.withRateFactor(0.95).rate() >= 0.80);
    }
}
