package com.lianyu.service.character;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.AnalyzeCharacterImportRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CharacterImportService {

    private final ApiKeyVaultService vaultService;
    private final AiChatService aiChatService;

    /**
     * 用人设或聊天记录抽取角色设定。不落会话消息。
     * 必须使用用户自己配置的文本模型。
     */
    public Map<String, Object> analyze(Long userId, AnalyzeCharacterImportRequest request) {
        String prepared = CharacterImportSourceParser.prepare(request.getSourceText());
        String addressing = CharacterAddressing.sanitize(request.getUserAddressing());
        if (addressing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写角色最常用的称呼");
        }

        VaultEntryResponse vault = vaultService.resolvePreferredUserVault(userId);
        if (vault == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "未配置文本模型，请在设置中添加");
        }

        Map<String, Object> draft = new LinkedHashMap<>(
                aiChatService.analyzeCharacterImportWithVault(vault, prepared, addressing));
        Object prompt = draft.get("promptTemplate");
        draft.put("promptTemplate", CharacterAddressing.appendHint(
                prompt == null ? "" : String.valueOf(prompt), addressing));
        draft.put("userAddressing", addressing);
        return draft;
    }
}
