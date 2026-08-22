package com.lianyu.service.tools;

import com.lianyu.dao.entity.Character;
import com.lianyu.service.dto.AiChatRequest;
import java.util.Map;

/**
 * 单次 AI 请求内绑定角色与用户，供 {@link MemorySearchTool}、{@link WeatherTool} 等使用。
 */
public final class ChatToolContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ChatToolContext() {
    }

    public record Scope(Long userId, Long characterId, Map<String, Object> characterSettings, String userCity,
                        String characterName, String characterAvatarUrl) {
        public String effectiveCity() {
            if (characterSettings != null) {
                String fc = characterSettings.get("fictional_city") instanceof String s && !s.isBlank() ? s : null;
                if (fc != null) return fc;
                String city = characterSettings.get("city") instanceof String s && !s.isBlank() ? s : null;
                if (city != null) return city;
            }
            return userCity;
        }
    }

    public static void set(Long userId, Long characterId, Map<String, Object> characterSettings) {
        set(userId, characterId, characterSettings, null, null, null);
    }

    public static void set(Long userId, Long characterId, Map<String, Object> characterSettings, String userCity) {
        set(userId, characterId, characterSettings, userCity, null, null);
    }

    public static void set(Long userId, Long characterId, Map<String, Object> characterSettings, String userCity,
                           String characterName, String characterAvatarUrl) {
        if (userId == null || characterId == null) {
            clear();
            return;
        }
        CURRENT.set(new Scope(userId, characterId, characterSettings, userCity, characterName, characterAvatarUrl));
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static Scope require() {
        Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("Chat tool scope not set for this request");
        }
        return scope;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void bindTo(AiChatRequest request, Character character) {
        if (request == null || character == null || character.getId() == null) {
            return;
        }
        request.setChatToolCharacterId(character.getId());
        request.setToolCharacterSettings(character.getSettings());
        String name = character.getName();
        request.setChatToolCharacterName(name == null || name.isBlank() ? null : name.trim());
        String avatar = character.getAvatarUrl();
        request.setChatToolCharacterAvatarUrl(avatar == null || avatar.isBlank() ? null : avatar.trim());
    }
}
