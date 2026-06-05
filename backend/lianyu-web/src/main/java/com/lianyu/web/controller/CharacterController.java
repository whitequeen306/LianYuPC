package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.character.CharacterService;
import com.lianyu.service.square.CharacterSquareService;
import com.lianyu.service.square.SquareLikeService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.dto.CharacterResponse;
import com.lianyu.service.dto.AddCharacterFromSquareRequest;
import com.lianyu.service.dto.CreateCharacterRequest;
import com.lianyu.service.dto.GenerateCharacterRequest;
import com.lianyu.service.dto.CharacterSquarePageResponse;
import com.lianyu.service.dto.SquareLikeToggleResponse;
import com.lianyu.service.dto.UpdateCharacterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Tag(name = "Character", description = "角色管理")
@RestController
@RequestMapping("/api/character")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;
    private final CharacterSquareService characterSquareService;
    private final SquareLikeService squareLikeService;
    private final FileStorageService fileStorageService;
    private final AiChatService aiChatService;

    @Operation(summary = "创建角色")
    @PostMapping
    public Result<CharacterResponse> create(@Valid @RequestBody CreateCharacterRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(characterService.create(userId, request));
    }

    @Operation(summary = "角色列表")
    @GetMapping
    public Result<List<CharacterResponse>> list() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(characterService.list(userId));
    }

    @Operation(summary = "角色广场模板列表（分页）")
    @GetMapping("/square")
    public Result<CharacterSquarePageResponse> listSquare(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword) {
        long userId = StpUtil.getLoginIdAsLong();
        String uiLang = request.getHeader(CharacterSquareService.HEADER_UI_LANGUAGE);
        int pageSize = size != null ? size : CharacterSquareService.SQUARE_PAGE_SIZE_DEFAULT;
        return Result.ok(characterSquareService.listTemplatesPage(userId, uiLang, tag, keyword, page, pageSize));
    }

    @Operation(summary = "从角色广场加入我的角色")
    @PostMapping("/square/{templateId}/add")
    public Result<CharacterResponse> addFromSquare(@PathVariable("templateId") Long templateId,
                                                   @Valid @RequestBody AddCharacterFromSquareRequest body,
                                                   HttpServletRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        String uiLang = request.getHeader(CharacterSquareService.HEADER_UI_LANGUAGE);
        return Result.ok(characterSquareService.addTemplateToMyCharacters(
                userId, templateId, uiLang, body.getCity()));
    }

    @Operation(summary = "角色广场点赞/取消点赞")
    @PostMapping("/square/{templateId}/like")
    public Result<SquareLikeToggleResponse> toggleSquareLike(@PathVariable("templateId") Long templateId) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(squareLikeService.toggleLike(userId, templateId));
    }

    @Operation(summary = "获取角色")
    @GetMapping("/{id}")
    public Result<CharacterResponse> get(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(characterService.get(userId, id));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    public Result<CharacterResponse> update(@PathVariable("id") Long id,
                                             @Valid @RequestBody UpdateCharacterRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(characterService.update(userId, id, request));
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        characterService.delete(userId, id);
        return Result.ok();
    }

    @Operation(summary = "上传角色头像")
    @PostMapping("/{id}/avatar")
    public Result<CharacterResponse> uploadAvatar(@PathVariable("id") Long id,
                                                   @RequestParam("file") MultipartFile file) {
        long userId = StpUtil.getLoginIdAsLong();
        String avatarUrl = fileStorageService.uploadAvatar(file);
        UpdateCharacterRequest updateReq = new UpdateCharacterRequest();
        updateReq.setAvatarUrl(avatarUrl);
        return Result.ok(characterService.update(userId, id, updateReq));
    }

    @Operation(summary = "上传角色聊天背景图")
    @PostMapping("/{id}/chat-background")
    public Result<CharacterResponse> uploadChatBackground(@PathVariable("id") Long id,
                                                          @RequestParam("file") MultipartFile file) {
        long userId = StpUtil.getLoginIdAsLong();
        String backgroundUrl = fileStorageService.uploadChatBackground(file);
        UpdateCharacterRequest updateReq = new UpdateCharacterRequest();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("chatBackgroundImageUrl", backgroundUrl);
        settings.put("useGlobalChatBackground", false);
        updateReq.setSettings(settings);
        return Result.ok(characterService.update(userId, id, updateReq));
    }

    @Operation(summary = "AI生成角色设定")
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@Valid @RequestBody GenerateCharacterRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(aiChatService.generateCharacter(userId, request));
    }
}
