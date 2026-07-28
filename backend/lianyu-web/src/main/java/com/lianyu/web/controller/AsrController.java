package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.dto.AsrTranscribeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "ASR", description = "语音转文字")
@RestController
@RequestMapping("/api/asr")
@RequiredArgsConstructor
public class AsrController {

    private final AsrService asrService;
    private final AuthRateLimiter authRateLimiter;

    @Operation(summary = "语音转文字", description = "上传短音频，返回识别文本（用于聊天填框或通话）")
    @PostMapping("/transcribe")
    public Result<AsrTranscribeResponse> transcribe(@RequestParam("file") MultipartFile file) {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        authRateLimiter.checkRateLimit("rate:asr:", String.valueOf(userId),
                30, Duration.ofMinutes(1), "语音识别过于频繁，请稍后再试");
        String text = asrService.transcribe(file);
        return Result.ok(AsrTranscribeResponse.builder().text(text).build());
    }
}
