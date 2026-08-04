package com.lianyu.service.voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Decode user audio (mp3/wav/...) and keep only the first {@code KEEP_SECONDS} seconds,
 * re-encoded as 16kHz mono WAV. Used for both the MinIO backup and DashScope enrollment,
 * so the original (possibly huge/long) file is never stored nor sent.
 */
@Slf4j
public final class CustomVoiceAudioTrimmer {

    /** Seconds kept from the start of the uploaded audio. */
    public static final double KEEP_SECONDS = 30.0;
    /** Give up if decoding takes too long (protects the request thread). */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    private CustomVoiceAudioTrimmer() {
    }

    /**
     * Returns trimmed 16k mono WAV bytes, or {@code null} when trimming is unavailable/failed
     * (caller decides whether to fall back to the raw upload).
     */
    public static byte[] trimToWav(byte[] inputBytes, String extension) {
        if (inputBytes == null || inputBytes.length == 0) {
            return null;
        }
        Path in = null;
        Path out = null;
        try {
            String ext = (extension == null || extension.isBlank()) ? "bin"
                    : extension.toLowerCase(Locale.ROOT);
            in = Files.createTempFile("custom-voice-in-", "." + ext);
            out = Files.createTempFile("custom-voice-out-", ".wav");
            Files.write(in, inputBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", in.toAbsolutePath().toString(),
                    "-t", String.valueOf(KEEP_SECONDS),
                    "-ac", "1", "-ar", "16000",
                    "-f", "wav", out.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Custom voice trim timed out");
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(out)) {
                log.warn("Custom voice trim failed exit={}", p.exitValue());
                return null;
            }
            byte[] wav = Files.readAllBytes(out);
            if (wav.length == 0) {
                return null;
            }
            return wav;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Custom voice trim error: {}", e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    Files.deleteIfExists(in);
                } catch (IOException ignored) {
                    // best effort temp cleanup
                }
            }
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // best effort temp cleanup
                }
            }
        }
    }
}
