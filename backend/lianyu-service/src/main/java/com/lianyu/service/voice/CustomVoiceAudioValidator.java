package com.lianyu.service.voice;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates user-uploaded voice samples (mp3/wav) for custom voice enrollment.
 */
public final class CustomVoiceAudioValidator {

    public static final long MAX_BYTES = 15L * 1024 * 1024;
    public static final long MIN_BYTES = 8L * 1024;
    public static final double MIN_SECONDS = 5.0;
    public static final double MAX_SECONDS = 120.0;

    private static final Set<String> ALLOWED_EXT = Set.of("mp3", "wav");

    private CustomVoiceAudioValidator() {
    }

    public record ValidatedSample(byte[] bytes, String extension, String contentType, Double durationSec) {
    }

    public static ValidatedSample validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传音频文件（mp3 或 wav）");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "音频不能超过 15MB");
        }
        if (file.getSize() < MIN_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "音频文件过小，请提供更长的清晰语音");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null || !ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "仅支持 mp3 或 wav 文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "读取音频失败，请重试");
        }
        if (!matchesMagic(bytes, ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "音频内容与扩展名不符");
        }
        Double duration = null;
        if ("wav".equals(ext)) {
            duration = wavDurationSec(bytes);
            if (duration != null && (duration < MIN_SECONDS || duration > MAX_SECONDS)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "音频时长需在 " + (int) MIN_SECONDS + "–" + (int) MAX_SECONDS + " 秒之间");
            }
        }
        String contentType = "mp3".equals(ext) ? "audio/mpeg" : "audio/wav";
        return new ValidatedSample(bytes, ext, contentType, duration);
    }

    static String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String name = filename.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }

    static boolean matchesMagic(byte[] bytes, String ext) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        if ("wav".equals(ext)) {
            return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
        }
        // MP3: ID3 or frame sync 0xFFEx
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0;
    }

    static Double wavDurationSec(byte[] bytes) {
        try {
            // Minimal PCM WAV: find "fmt " and "data"
            int fmt = indexOf(bytes, new byte[]{'f', 'm', 't', ' '}, 12);
            int data = indexOf(bytes, new byte[]{'d', 'a', 't', 'a'}, 12);
            if (fmt < 0 || data < 0 || fmt + 24 > bytes.length || data + 8 > bytes.length) {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int audioFormat = bb.getShort(fmt + 8) & 0xFFFF;
            int channels = bb.getShort(fmt + 10) & 0xFFFF;
            int sampleRate = bb.getInt(fmt + 12);
            int byteRate = bb.getInt(fmt + 16);
            int dataSize = bb.getInt(data + 4);
            if (audioFormat == 0 || channels <= 0 || sampleRate <= 0 || byteRate <= 0 || dataSize <= 0) {
                return null;
            }
            return dataSize / (double) byteRate;
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
