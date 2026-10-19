package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.CharacterSettingsUtils;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.agent.CharacterGenerationPort;
import com.lianyu.service.character.CharacterImportDraftMapper;
import com.lianyu.service.character.CharacterImportSourceParser;
import com.lianyu.service.dto.GenerateCharacterRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 角色设定生成 / 抽取的 Java 门面。
 *
 * <p>模型调用与 prompt 全部在 backend-agent；Java 只做 vault 解析、输入清洗与
 * JSON 字段归一化。对话/摘要/图片/阻塞调用已迁到各自的 agent 端口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final ApiKeyVaultService vaultService;
    private final CharacterGenerationPort characterGenerationPort;
    private final ObjectMapper objectMapper;

    public Map<String, Object> generateCharacter(Long userId, GenerateCharacterRequest request) {
        VaultEntryResponse vault = resolveVault(userId,
                request != null ? request.getProvider() : null);
        String safeDescription = UserInputSanitizer.sanitizeGenerationDescription(
                request != null ? request.getDescription() : null);
        try {
            CharacterGenerationPort.Result result =
                    characterGenerationPort.generate(userId, vault, safeDescription);
            if (result == null || result.content() == null || result.content().isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                        "生成失败，请换个描述再试");
            }
            Map<String, Object> parsed = parseGeneratedCharacter(result.content());
            parsed.put("provider", vault.getProvider());
            parsed.put("model", result.model());
            return parsed;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            String reason = simplifyGenerationError(e);
            log.warn("Character generation failed with provider={}, reason={}", vault.getProvider(), reason);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "角色设定生成失败，请稍后再试", e);
        }
    }

    /**
     * 从人设或聊天记录抽取固定 JSON 角色草稿。
     */
    public Map<String, Object> analyzeCharacterImportWithVault(VaultEntryResponse vault,
                                                               String preparedSource) {
        return analyzeCharacterImportWithVault(vault, preparedSource, "");
    }

    public Map<String, Object> analyzeCharacterImportWithVault(VaultEntryResponse vault,
                                                               String preparedSource,
                                                               String userAddressing) {
        if (vault == null || vault.getId() == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "未配置文本模型，请在设置中添加");
        }
        CharacterGenerationPort.Result result;
        try {
            result = characterGenerationPort.importDraft(
                    vault.getUserId(), vault, preparedSource, userAddressing);
        } catch (Exception e) {
            log.warn("Character import failed with provider={}, reason={}",
                    vault.getProvider(), simplifyGenerationError(e));
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "角色抽取失败，请稍后再试", e);
        }
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "角色抽取失败，请稍后再试");
        }
        Map<String, Object> draft = CharacterImportDraftMapper.parse(objectMapper, result.content());
        draft.put("provider", vault.getProvider());
        draft.put("model", result.model());
        return draft;
    }

    private Map<String, Object> parseGeneratedCharacter(String content) {
        String cleaned = extractJsonObject(content.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "生成结果格式异常，请重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", fix(valueOrDefault(root, "name", "未命名角色")));
        result.put("age", fix(valueOrDefault(root, "age", "未知")));
        result.put("gender", fix(valueOrDefault(root, "gender", "未知")));
        result.put("speakingStyle", fix(valueOrDefault(root, "speakingStyle", "温柔")));
        result.put("promptTemplate", fix(valueOrDefault(root, "promptTemplate", content.trim())));
        return result;
    }

    VaultEntryResponse resolveVault(Long userId, String provider) {
        if (isPlatformProvider(provider)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加");
        }
        VaultEntryResponse userVault = vaultService.resolveForChat(userId, provider);
        if (userVault == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "找不到该 AI 配置，请先在设置中添加");
        }
        log.info("AI vault: source=DB_USER, userId={}, scope={}, vaultId={}, provider={}",
                userId, userVault.getVaultScope(), userVault.getId(), userVault.getProvider());
        return userVault;
    }

    private boolean isPlatformProvider(String provider) {
        return provider == null
                || provider.isBlank()
                || AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(provider);
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String valueOrDefault(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String value = node.asText("");
        return value.isBlank() ? defaultValue : value.trim();
    }

    private static String fix(String value) {
        return CharacterSettingsUtils.fixUtf8Mojibake(value);
    }

    private String simplifyGenerationError(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof java.nio.channels.UnresolvedAddressException
                || root instanceof java.net.UnknownHostException
                || root instanceof java.net.ConnectException) {
            return "连接失败（请检查 Base URL 是否可访问）";
        }
        return e.getMessage() != null ? e.getMessage() : "未知错误";
    }
}
