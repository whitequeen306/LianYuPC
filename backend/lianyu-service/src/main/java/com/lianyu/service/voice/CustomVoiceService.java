package com.lianyu.service.voice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.UserCustomVoice;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.UserCustomVoiceMapper;
import com.lianyu.security.util.JasyptUtil;
import com.lianyu.service.ai.DashScopeVoiceEnrollmentClient;
import com.lianyu.service.dto.CustomVoiceResponse;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.voice.CustomVoiceAudioValidator.ValidatedSample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomVoiceService {

    private final UserCustomVoiceMapper customVoiceMapper;
    private final CharacterMapper characterMapper;
    private final FileStorageService fileStorageService;
    private final DashScopeVoiceEnrollmentClient enrollmentClient;
    private final JasyptUtil jasyptUtil;

    public CustomVoiceResponse get(Long userId, Long characterId) {
        findOwnedCharacter(userId, characterId);
        UserCustomVoice row = findRow(userId, characterId);
        if (row == null) {
            return null;
        }
        return toResponse(row);
    }

    /**
     * Create/replace custom voice for a character. Requires audio + provider (+ key or local config).
     */
    @Transactional
    public CustomVoiceResponse upsert(
            Long userId,
            Long characterId,
            String providerRaw,
            MultipartFile audio,
            String apiKey,
            String refText,
            String endpoint) {
        Character character = findOwnedCharacter(userId, characterId);
        String provider = CustomVoiceProviders.normalize(providerRaw);
        if (provider == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的语音模型类型");
        }
        ValidatedSample sample = CustomVoiceAudioValidator.validate(audio);

        UserCustomVoice existing = findRow(userId, characterId);
        // Delete previous cloud voices / object before replace
        if (existing != null) {
            cleanupRemote(existing);
        }

        String objectKey = fileStorageService.uploadCustomVoiceBytes(
                userId, sample.bytes(), sample.contentType(), sample.extension());

        UserCustomVoice row = existing != null ? existing : new UserCustomVoice();
        row.setUserId(userId);
        row.setCharacterId(characterId);
        row.setProvider(provider);
        row.setRefAudioObjectKey(objectKey);
        row.setHttpVoiceId(null);
        row.setRealtimeVoiceId(null);
        row.setErrorMessage(null);
        row.setStatus(CustomVoiceProviders.STATUS_PENDING);

        if (CustomVoiceProviders.DASHSCOPE_VC.equals(provider)) {
            enrollDashScope(row, character, sample, apiKey);
        } else if (CustomVoiceProviders.GPTSOVITS_LOCAL.equals(provider)) {
            configureLocal(row, refText, endpoint);
        }

        if (existing == null) {
            customVoiceMapper.insert(row);
        } else {
            customVoiceMapper.updateById(row);
        }
        log.info("Custom voice upsert userId={} characterId={} provider={} status={}",
                userId, characterId, provider, row.getStatus());
        return toResponse(row);
    }

    @Transactional
    public void delete(Long userId, Long characterId) {
        findOwnedCharacter(userId, characterId);
        UserCustomVoice row = findRow(userId, characterId);
        if (row == null) {
            return;
        }
        cleanupRemote(row);
        customVoiceMapper.deleteById(row.getId());
        log.info("Custom voice deleted userId={} characterId={}", userId, characterId);
    }

    /** READY custom voice for call resolution; null if none. */
    public UserCustomVoice findReady(Long userId, Long characterId) {
        UserCustomVoice row = findRow(userId, characterId);
        if (row == null || !CustomVoiceProviders.STATUS_READY.equalsIgnoreCase(row.getStatus())) {
            return null;
        }
        return row;
    }

    /** Character ids with READY custom voice for this user (for list response flags). */
    public java.util.Set<Long> findReadyCharacterIds(Long userId) {
        if (userId == null) {
            return java.util.Set.of();
        }
        return customVoiceMapper.selectList(new LambdaQueryWrapper<UserCustomVoice>()
                        .eq(UserCustomVoice::getUserId, userId)
                        .eq(UserCustomVoice::getStatus, CustomVoiceProviders.STATUS_READY)
                        .select(UserCustomVoice::getCharacterId))
                .stream()
                .map(UserCustomVoice::getCharacterId)
                .collect(java.util.stream.Collectors.toSet());
    }

    public String decryptApiKey(UserCustomVoice row) {
        if (row == null || row.getApiKeyEncrypted() == null || row.getApiKeyEncrypted().isBlank()) {
            return null;
        }
        try {
            return jasyptUtil.decrypt(row.getApiKeyEncrypted());
        } catch (Exception e) {
            log.warn("Custom voice api key decrypt failed id={}", row.getId());
            return null;
        }
    }

    private void enrollDashScope(UserCustomVoice row, Character character, ValidatedSample sample, String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "DashScope 模式需提供 API Key");
        }
        if (key.length() < 16 || key.length() > 256) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 长度无效");
        }
        row.setApiKeyEncrypted(jasyptUtil.encrypt(key));
        row.setKeyVersion(jasyptUtil.getCurrentVersion());
        row.setRefText(null);
        row.setEndpoint(null);

        String preferred = "u" + row.getUserId() + "_c" + character.getId();
        try {
            String httpVoice = enrollmentClient.createVoice(
                    key, DashScopeVoiceEnrollmentClient.HTTP_MODEL, preferred,
                    sample.bytes(), sample.contentType(), "zh");
            String rtVoice = enrollmentClient.createVoice(
                    key, DashScopeVoiceEnrollmentClient.REALTIME_MODEL, preferred + "_rt",
                    sample.bytes(), sample.contentType(), "zh");
            row.setHttpVoiceId(httpVoice);
            row.setRealtimeVoiceId(rtVoice);
            row.setStatus(CustomVoiceProviders.STATUS_READY);
        } catch (BusinessException e) {
            row.setStatus(CustomVoiceProviders.STATUS_FAILED);
            row.setErrorMessage(trimErr(e.getMessage()));
            // Still persist FAILED row so UI can show reason; rethrow after save happens in caller
            // Actually we save after this method — throw so transaction can still commit if we want
            // Prefer: set FAILED and return without throw so user sees status
            log.warn("Custom DashScope enroll failed characterId={}: {}", character.getId(), e.getMessage());
        }
        if (CustomVoiceProviders.STATUS_FAILED.equals(row.getStatus())) {
            // Keep FAILED record for UX; don't throw — caller returns response
            return;
        }
    }

    private void configureLocal(UserCustomVoice row, String refText, String endpoint) {
        String text = UserInputSanitizer.sanitizeGenerationDescription(refText == null ? "" : refText);
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地模型需填写参考文本（与音频内容一致）");
        }
        if (text.length() > 2000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参考文本过长");
        }
        String ep = LocalTtsEndpointValidator.normalizeAndValidate(endpoint);
        row.setRefText(text.trim());
        row.setEndpoint(ep);
        row.setApiKeyEncrypted(null);
        row.setKeyVersion(null);
        row.setHttpVoiceId(null);
        row.setRealtimeVoiceId(null);
        row.setStatus(CustomVoiceProviders.STATUS_READY);
    }

    private void cleanupRemote(UserCustomVoice row) {
        if (row.getRefAudioObjectKey() != null) {
            fileStorageService.deleteObjectQuietly(row.getRefAudioObjectKey());
        }
        if (CustomVoiceProviders.DASHSCOPE_VC.equalsIgnoreCase(row.getProvider())) {
            String key = decryptApiKey(row);
            enrollmentClient.deleteVoiceQuietly(key, row.getHttpVoiceId());
            enrollmentClient.deleteVoiceQuietly(key, row.getRealtimeVoiceId());
        }
    }

    private UserCustomVoice findRow(Long userId, Long characterId) {
        return customVoiceMapper.selectOne(new LambdaQueryWrapper<UserCustomVoice>()
                .eq(UserCustomVoice::getUserId, userId)
                .eq(UserCustomVoice::getCharacterId, characterId)
                .last("LIMIT 1"));
    }

    private Character findOwnedCharacter(Long userId, Long characterId) {
        Character entity = characterMapper.selectById(characterId);
        if (entity == null || !userId.equals(entity.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        return entity;
    }

    private CustomVoiceResponse toResponse(UserCustomVoice row) {
        boolean ready = CustomVoiceProviders.STATUS_READY.equalsIgnoreCase(row.getStatus());
        boolean voiceCallReady = ready && (
                (CustomVoiceProviders.DASHSCOPE_VC.equalsIgnoreCase(row.getProvider())
                        && row.getRealtimeVoiceId() != null && !row.getRealtimeVoiceId().isBlank())
                        || (CustomVoiceProviders.GPTSOVITS_LOCAL.equalsIgnoreCase(row.getProvider())
                        && row.getEndpoint() != null && row.getRefText() != null
                        && row.getRefAudioObjectKey() != null));
        return CustomVoiceResponse.builder()
                .characterId(row.getCharacterId())
                .provider(row.getProvider())
                .status(row.getStatus())
                .errorMessage(row.getErrorMessage())
                .refAudioUrl(fileStorageService.resolvePublicUrl(row.getRefAudioObjectKey()))
                .refText(row.getRefText())
                .endpoint(row.getEndpoint())
                .hasApiKey(row.getApiKeyEncrypted() != null && !row.getApiKeyEncrypted().isBlank())
                .voiceCallReady(voiceCallReady)
                .build();
    }

    private static String trimErr(String msg) {
        if (msg == null) {
            return "语音配置失败";
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
