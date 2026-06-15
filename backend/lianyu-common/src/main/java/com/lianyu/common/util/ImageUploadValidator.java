package com.lianyu.common.util;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * 上传图片校验：解码验证、尺寸限制、重编码为 PNG（剥离嵌入内容）。
 */
public final class ImageUploadValidator {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;

    private ImageUploadValidator() {
    }

    public record ValidatedImage(byte[] pngBytes) {
    }

    public static ValidatedImage validateAndReencode(InputStream input, long declaredSize) {
        if (declaredSize > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图片大小不能超过 2MB");
        }
        byte[] raw;
        try {
            raw = input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "无法读取图片文件");
        }
        if (raw.length == 0 || raw.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图片大小不能超过 2MB");
        }
        if (!looksLikeImage(raw)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "仅支持 JPG / PNG / WebP / GIF 图片");
        }
        BufferedImage image = decodeImage(raw);
        if (image == null) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "图片格式无效或已损坏");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片尺寸无效或超过限制");
        }
        BufferedImage normalized = normalizeToArgb(image);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(normalized, "png", out)) {
                throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "图片重编码失败");
            }
            byte[] png = out.toByteArray();
            if (png.length > MAX_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图片处理后仍超过大小限制");
            }
            return new ValidatedImage(png);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_TYPE_DENIED, "图片处理失败");
        }
    }

    private static boolean looksLikeImage(byte[] raw) {
        if (raw.length >= 8 && raw[0] == (byte) 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47) {
            return true; // PNG
        }
        if (raw.length >= 3 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xD8 && raw[2] == (byte) 0xFF) {
            return true; // JPEG
        }
        if (raw.length >= 6 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F') {
            return true; // GIF
        }
        if (raw.length >= 12
                && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P') {
            return true; // WebP
        }
        return false;
    }

    private static BufferedImage decodeImage(byte[] raw) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(raw)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage normalizeToArgb(BufferedImage source) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, target.getWidth(), target.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return target;
    }
}
