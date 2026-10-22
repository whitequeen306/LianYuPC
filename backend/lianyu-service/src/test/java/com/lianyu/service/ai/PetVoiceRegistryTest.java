package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 音色注册表的契约测试。
 *
 * <p>2026-10-21：旧的 {@code qwen3-tts-vc-*} 模型系列已下架，13 个角色音色全部按当时的
 * 现役模型 {@code cosyvoice-v3-flash} 重新注册，实时音色随之清空（平台已无「支持复刻的
 * 实时 TTS 模型」）。
 *
 * <p>2026-10-22：用户反馈"音色漂移、不太像角色"。根因是参考音频用了 40/55 秒的拼接素材
 * （静音占比 27-36%、混着不同情绪），而官方建议 5-20 秒。改为「自动精选 18 秒」并把模型
 * 换到 {@code cosyvoice-v3.5-flash}（同句合成基频与参考音频差从 28Hz 收到 3Hz）。
 */
class PetVoiceRegistryTest {

    private static final String HTTP_MODEL = "cosyvoice-v3.5-flash";

    private static final String[] PETS = {
            "klee", "ganyu", "ayaka", "raiden", "elysia", "erii_uesugi",
            "yae_miko", "kokomi", "shenhe", "nahida", "hu_tao", "furina", "noelle",
    };

    private static PetVoiceRegistry load() {
        PetVoiceRegistry registry = new PetVoiceRegistry(new ObjectMapper());
        registry.load();
        return registry;
    }

    @Test
    void loadsAllThirteenPetVoices() {
        PetVoiceRegistry registry = load();
        for (String pet : PETS) {
            assertTrue(registry.hasVoice(pet), "缺少音色: " + pet);
            assertTrue(registry.resolveHttpVoiceId(pet) != null, "解析不到 voiceId: " + pet);
        }
        assertEquals(HTTP_MODEL, registry.getModel());
        assertNull(registry.resolveHttpVoiceId("kurumi"));
        assertNull(registry.resolveVoiceId("unknown-pet"));
    }

    /**
     * 守护断言：每个 voiceId 必须绑定在当前模型上。
     *
     * <p>DashScope 的 voiceId 里嵌了注册时的 target_model（例如
     * {@code qwen-audio-3.1-tts-flash-lyklee-9565…}）。这条断言能在「换了模型名却没
     * 重新注册音色」时立刻炸出来 —— 那正是这次线上故障的根因
     * （模型下架后 26 个旧 voiceId 全部报 voice does not exist）。
     */
    @Test
    void everyVoiceIsBoundToTheCurrentModel() {
        PetVoiceRegistry registry = load();
        for (String pet : PETS) {
            String voiceId = registry.resolveHttpVoiceId(pet);
            assertTrue(
                    voiceId.startsWith(HTTP_MODEL + "-"),
                    "音色 " + voiceId + " 不是按 " + HTTP_MODEL + " 注册的（音色与模型不一致会导致合成失败）");
        }
    }

    /** 实时音色已退役：注册表必须为空，且查询一律返回 null（不能悄悄回退到 HTTP id）。 */
    @Test
    void realtimeVoicesAreRetired() {
        PetVoiceRegistry registry = load();
        assertEquals("", registry.getRealtimeModel());
        for (String pet : PETS) {
            assertFalse(registry.hasRealtimeVoice(pet), "不应再有实时音色: " + pet);
            assertNull(registry.resolveRealtimeVoiceId(pet), "实时音色应为空: " + pet);
        }
    }

    /** petId → voiceId 必须一一不同，避免注册时把同一个音色复用给多个角色。 */
    @Test
    void voiceIdsAreDistinctPerPet() {
        PetVoiceRegistry registry = load();
        Map<String, String> seen = new java.util.HashMap<>();
        for (String pet : PETS) {
            String voiceId = registry.resolveHttpVoiceId(pet);
            String previous = seen.put(voiceId, pet);
            assertNull(previous, "音色被重复使用: " + pet + " 与 " + previous + " -> " + voiceId);
        }
    }
}
