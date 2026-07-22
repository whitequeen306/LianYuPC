package com.lianyu.service.storage;

import com.lianyu.common.util.ImageUploadValidator;
import com.lianyu.storage.minio.MinioImageProcessor;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    public static final String PUBLIC_FILE_PREFIX = "/api/public/files/";

    private final MinioClient minioClient;
    private final com.lianyu.storage.minio.MinioConfig minioConfig;

    private static final String AVATAR_PATH = "avatars/";
    private static final String CHAT_IMAGE_PATH = "chat-images/";
    private static final String COMMUNITY_IMAGE_PATH = "community-images/";
    private static final String CHAT_VOICE_PATH = "chat-voice/";
    private static final String SQUARE_AVATAR_PATH = "square-avatars/";
    private static final String SQUARE_AVATAR_THUMB_PATH = "square-avatars-thumb/";
    private static final String UPDATES_PATH = "updates/";
    private static final int SQUARE_AVATAR_THUMB_SIZE = 296;
    private static final long AVATAR_MAX_BYTES = ImageUploadValidator.MAX_BYTES;
    private static final String AVATAR_CONTENT_TYPE = "image/png";
    private static final long CHAT_IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final long CHAT_VOICE_MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> CHAT_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final Pattern SAFE_PET_ID = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern SAFE_OBJECT_KEY = Pattern.compile(
            "^(avatars/[a-zA-Z0-9._-]+|chat-images/[a-zA-Z0-9._-]+|community-images/[a-zA-Z0-9._-]+|chat-voice/[a-z0-9-]+/[a-zA-Z0-9._-]+|square-avatars/[a-z0-9._-]+|square-avatars-thumb/[a-z0-9._-]+|updates/(latest\\.yml|[a-zA-Z0-9._-]+\\.exe|[a-zA-Z0-9._-]+\\.exe\\.blockmap))$"
    );

    public String uploadAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "请选择图片文件");
        }
        try {
            ImageUploadValidator.ValidatedImage validated = ImageUploadValidator.validateAndReencode(
                    file.getInputStream(), file.getSize());
            String objectName = AVATAR_PATH + UUID.randomUUID().toString().replace("-", "") + ".png";
            byte[] pngBytes = validated.pngBytes();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(new java.io.ByteArrayInputStream(pngBytes), pngBytes.length, -1)
                            .contentType(AVATAR_CONTENT_TYPE)
                            .build()
            );

            String publicUrl = toPublicUrl(objectName);
            log.info("Avatar uploaded: {} -> {} ({} bytes)", objectName, publicUrl, pngBytes.length);
            return publicUrl;
        } catch (com.lianyu.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Avatar upload failed", e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "头像上传失败，请稍后再试");
        }
    }

    public String uploadChatBackground(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "请选择图片文件");
        }
        try {
            ImageUploadValidator.ValidatedImage validated = ImageUploadValidator.validateAndReencode(
                    file.getInputStream(), file.getSize());
            String objectName = AVATAR_PATH + "chatbg-" + UUID.randomUUID().toString().replace("-", "") + ".png";
            byte[] pngBytes = validated.pngBytes();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(new java.io.ByteArrayInputStream(pngBytes), pngBytes.length, -1)
                            .contentType(AVATAR_CONTENT_TYPE)
                            .build()
            );

            String publicUrl = toPublicUrl(objectName);
            log.info("Chat background uploaded: {} -> {} ({} bytes)", objectName, publicUrl, pngBytes.length);
            return publicUrl;
        } catch (com.lianyu.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Chat background upload failed", e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "聊天背景上传失败，请稍后再试");
        }
    }

    /**
     * 根据 slug 与源文件名推断广场头像 object key（与 {@link #uploadSquareAvatar} 规则一致）。
     */
    public String resolveSquareAvatarObjectKey(String slug, String filename) {
        if (slug == null || !slug.matches("[a-z0-9_]+")) {
            return null;
        }
        String ext = getExtension(filename);
        if (ext == null || ext.isBlank()) {
            ext = ".jpg";
        }
        return SQUARE_AVATAR_PATH + slug + ext;
    }

    public boolean objectExists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            statObject(objectKey);
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || e.response() != null && e.response().code() == 404) {
                return false;
            }
            log.warn("MinIO stat failed for {}: {}", objectKey, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("MinIO objectExists failed for {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    public long objectSize(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return -1L;
        }
        try {
            return statObject(objectKey).size();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 上传角色广场预置头像，object key 固定为 square-avatars/{slug}{ext}。
     */
    public String uploadSquareAvatar(String slug, byte[] bytes, String contentType) {
        if (slug == null || !slug.matches("[a-z0-9_]+")) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "无效的角色标识");
        }
        String ext = switch (contentType != null ? contentType : "") {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
        String objectName = SQUARE_AVATAR_PATH + slug + ext;
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType(contentType != null ? contentType : "image/jpeg")
                            .build()
            );
            uploadSquareAvatarThumbIfMissing(slug, bytes);
            log.info("Square avatar uploaded: slug={} -> {}", slug, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Square avatar upload failed: slug={}", slug, e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "广场头像上传失败，请稍后再试");
        }
    }

    /** 根据原图 object key 推断缩略图 key（统一为 .jpg） */
    public String resolveSquareAvatarThumbObjectKey(String avatarObjectKey) {
        if (avatarObjectKey == null || !avatarObjectKey.startsWith(SQUARE_AVATAR_PATH)) {
            return null;
        }
        String base = avatarObjectKey.substring(SQUARE_AVATAR_PATH.length());
        int dot = base.lastIndexOf('.');
        String slugPart = dot >= 0 ? base.substring(0, dot) : base;
        return SQUARE_AVATAR_THUMB_PATH + slugPart + ".jpg";
    }

    public String resolveSquareAvatarThumbPublicUrl(String storedAvatar) {
        String objectKey = extractObjectKey(storedAvatar);
        if (objectKey == null || !objectKey.startsWith(SQUARE_AVATAR_PATH)) {
            return resolvePublicUrl(storedAvatar);
        }
        String thumbKey = resolveSquareAvatarThumbObjectKey(objectKey);
        if (thumbKey != null && objectExists(thumbKey)) {
            return toPublicUrl(thumbKey);
        }
        return resolvePublicUrl(storedAvatar);
    }

    /** 启动同步 / 回填：原图存在但缩略图缺失时补生成 */
    public boolean ensureSquareAvatarThumb(String avatarObjectKey) {
        if (avatarObjectKey == null || avatarObjectKey.isBlank()) {
            return false;
        }
        String objectKey = extractObjectKey(avatarObjectKey);
        if (objectKey == null || !objectKey.startsWith(SQUARE_AVATAR_PATH)) {
            return false;
        }
        String thumbKey = resolveSquareAvatarThumbObjectKey(objectKey);
        if (thumbKey == null || objectExists(thumbKey)) {
            return false;
        }
        try {
            byte[] source = readObjectBytes(objectKey);
            uploadSquareAvatarThumbFromSource(objectKey, source);
            return true;
        } catch (Exception e) {
            log.warn("Square avatar thumb backfill failed for {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    private void uploadSquareAvatarThumbIfMissing(String slug, byte[] sourceBytes) {
        String thumbKey = SQUARE_AVATAR_THUMB_PATH + slug + ".jpg";
        if (objectExists(thumbKey)) {
            return;
        }
        uploadSquareAvatarThumbBytes(thumbKey, sourceBytes);
    }

    private void uploadSquareAvatarThumbFromSource(String avatarObjectKey, byte[] sourceBytes) {
        String thumbKey = resolveSquareAvatarThumbObjectKey(avatarObjectKey);
        if (thumbKey == null) {
            return;
        }
        uploadSquareAvatarThumbBytes(thumbKey, sourceBytes);
    }

    private void uploadSquareAvatarThumbBytes(String thumbKey, byte[] sourceBytes) {
        byte[] thumbBytes = MinioImageProcessor.cropSquareThumb(sourceBytes, SQUARE_AVATAR_THUMB_SIZE);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(thumbKey)
                            .stream(new java.io.ByteArrayInputStream(thumbBytes), thumbBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );
            log.info("Square avatar thumb uploaded: {}", thumbKey);
        } catch (Exception e) {
            log.error("Square avatar thumb upload failed: {}", thumbKey, e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "广场头像缩略图生成失败");
        }
    }

    public String uploadChatImage(MultipartFile file) {
        return uploadImageToPath(file, CHAT_IMAGE_PATH, "Chat");
    }

    public String uploadCommunityImage(MultipartFile file) {
        return uploadImageToPath(file, COMMUNITY_IMAGE_PATH, "Community");
    }

    private String uploadImageToPath(MultipartFile file, String pathPrefix, String logLabel) {
        validateChatImage(file);
        try {
            String ext = getExtension(file.getOriginalFilename());
            String objectName = pathPrefix + UUID.randomUUID().toString().replace("-", "") + ext;
            String contentType = normalizeImageContentType(file);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );

            String publicUrl = toPublicUrl(objectName);
            log.info("{} image uploaded: {} -> {} ({} bytes)", logLabel, objectName, publicUrl, file.getSize());
            return publicUrl;
        } catch (com.lianyu.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} image upload failed", logLabel, e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "图片上传失败，请稍后再试");
        }
    }

    /**
     * Upload server-generated chat voice (TTS). Returns object key for DB storage.
     * {@code petId} is embedded in the path for client playback-rate lookup.
     */
    public String uploadChatVoiceBytes(String petId, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "语音数据为空");
        }
        if (bytes.length > CHAT_VOICE_MAX_BYTES) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "语音文件过大");
        }
        if (petId == null || !SAFE_PET_ID.matcher(petId).matches()) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "无效的语音角色");
        }
        String mime = normalizeVoiceContentType(contentType);
        String ext = mime.contains("mpeg") || mime.contains("mp3") ? ".mp3" : ".wav";
        String objectName = CHAT_VOICE_PATH + petId + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType(mime)
                            .build()
            );
            log.info("Chat voice uploaded: {} ({} bytes)", objectName, bytes.length);
            return objectName;
        } catch (com.lianyu.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Chat voice upload failed", e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.UPLOAD_FAILED, "语音上传失败，请稍后再试");
        }
    }

    private static String normalizeVoiceContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "audio/wav";
        }
        String lower = contentType.toLowerCase().trim();
        if (lower.startsWith("audio/wav") || lower.equals("audio/x-wav") || lower.equals("audio/wave")) {
            return "audio/wav";
        }
        if (lower.startsWith("audio/mpeg") || lower.equals("audio/mp3")) {
            return "audio/mpeg";
        }
        return "audio/wav";
    }

    public byte[] readObjectBytes(String objectKey) {
        validateObjectKey(objectKey);
        try (GetObjectResponse object = getObject(objectKey);
             InputStream in = object) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.error("Read object failed: {}", objectKey, e);
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.NOT_FOUND, "图片读取失败");
        }
    }

    public String resolveContentType(String objectKey) {
        try {
            StatObjectResponse stat = statObject(objectKey);
            String contentType = stat.contentType();
            if (contentType != null && !contentType.isBlank()) {
                return contentType;
            }
        } catch (Exception e) {
            log.debug("stat object for content type failed: {}", objectKey);
        }
        return guessContentTypeFromKey(objectKey);
    }

    public StatObjectResponse statObject(String objectKey) throws Exception {
        validateObjectKey(objectKey);
        return minioClient.statObject(
                StatObjectArgs.builder()
                        .bucket(minioConfig.getBucket())
                        .object(objectKey)
                        .build());
    }

    public GetObjectResponse getObject(String objectKey) throws Exception {
        validateObjectKey(objectKey);
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioConfig.getBucket())
                        .object(objectKey)
                        .build());
    }

    /** 将库中存的 MinIO 直链或 object key 转为浏览器可访问的 API 路径 */
    public String resolvePublicUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String trimmed = stored.trim();
        if (trimmed.startsWith(PUBLIC_FILE_PREFIX)) {
            return trimmed;
        }
        String objectKey = extractObjectKey(trimmed);
        if (objectKey != null) {
            return toPublicUrl(objectKey);
        }
        return trimmed;
    }

    public static String toPublicUrl(String objectKey) {
        return PUBLIC_FILE_PREFIX + objectKey;
    }

    public static String extractObjectKey(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (SAFE_OBJECT_KEY.matcher(stored).matches()) {
            return stored;
        }
        for (String prefix : new String[]{
                AVATAR_PATH, CHAT_IMAGE_PATH, COMMUNITY_IMAGE_PATH, CHAT_VOICE_PATH,
                SQUARE_AVATAR_PATH, SQUARE_AVATAR_THUMB_PATH, UPDATES_PATH}) {
            int idx = stored.indexOf(prefix);
            if (idx >= 0) {
                String key = stored.substring(idx);
                int q = key.indexOf('?');
                if (q >= 0) {
                    key = key.substring(0, q);
                }
                if (SAFE_OBJECT_KEY.matcher(key).matches()) {
                    return key;
                }
            }
        }
        return null;
    }

    public void deleteObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            validateObjectKey(objectKey);
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectKey)
                            .build());
            log.info("Object deleted: {}", objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete object: {}", objectKey);
        }
    }

    /** 公开文件的安全 Content-Type（拒绝 text/html 等危险类型） */
    public String resolveSafePublicContentType(String objectKey, String storedContentType) {
        if (objectKey == null) {
            return "application/octet-stream";
        }
        String lowerKey = objectKey.toLowerCase();
        if (lowerKey.startsWith(AVATAR_PATH) || lowerKey.startsWith(CHAT_IMAGE_PATH)
                || lowerKey.startsWith(COMMUNITY_IMAGE_PATH)
                || lowerKey.startsWith(SQUARE_AVATAR_PATH) || lowerKey.startsWith(SQUARE_AVATAR_THUMB_PATH)) {
            if (storedContentType != null) {
                String normalized = storedContentType.toLowerCase();
                if (normalized.startsWith("image/")) {
                    return normalized;
                }
            }
            return guessContentTypeFromKey(objectKey);
        }
        if (lowerKey.startsWith(CHAT_VOICE_PATH)) {
            if (storedContentType != null) {
                String normalized = storedContentType.toLowerCase();
                if (normalized.startsWith("audio/")) {
                    return normalized;
                }
            }
            return lowerKey.endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
        }
        if (lowerKey.equals(UPDATES_PATH + "latest.yml")) {
            return "text/yaml";
        }
        return "application/octet-stream";
    }

    public boolean isDangerousPublicContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase().trim();
        return normalized.contains("text/html")
                || normalized.contains("application/xhtml")
                || normalized.contains("image/svg")
                || normalized.startsWith("text/javascript")
                || normalized.contains("application/javascript");
    }

    public static void validateObjectKey(String objectKey) {
        if (objectKey == null || !SAFE_OBJECT_KEY.matcher(objectKey).matches()) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.NOT_FOUND, "文件不存在");
        }
    }

    private void validateChatImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "请选择图片文件");
        }
        if (file.getSize() > CHAT_IMAGE_MAX_BYTES) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "图片大小不能超过 5MB");
        }
        String contentType = normalizeImageContentType(file);
        if (!CHAT_IMAGE_TYPES.contains(contentType)) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.BAD_REQUEST, "仅支持 JPG / PNG / WebP / GIF 图片");
        }
    }

    private String normalizeImageContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            return contentType.toLowerCase();
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
        }
        return "image/jpeg";
    }

    private String guessContentTypeFromKey(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.equals(UPDATES_PATH + "latest.yml")) return "text/yaml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private String getExtension(String filename) {
        if (filename == null) return ".png";
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i) : ".png";
    }
}
