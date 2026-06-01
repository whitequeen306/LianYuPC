package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.GroupChatService;
import com.lianyu.service.dto.CharacterResponse;
import com.lianyu.service.dto.ConversationResponse;
import com.lianyu.service.dto.CreateGroupConversationRequest;
import com.lianyu.service.dto.SendMessageRequest;
import com.lianyu.service.dto.UpdateGroupTitleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@Tag(name = "GroupChat", description = "群聊管理")
@RestController
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupChatService groupChatService;

    @Operation(summary = "创建群聊")
    @PostMapping("/api/conversation/group")
    public Result<ConversationResponse> create(@Valid @RequestBody CreateGroupConversationRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(groupChatService.createGroup(userId, request));
    }

    @Operation(summary = "群聊成员列表")
    @GetMapping("/api/conversation/group/{id}/members")
    public Result<List<CharacterResponse>> listMembers(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(groupChatService.listMembers(userId, id));
    }

    @Operation(summary = "修改群聊名称")
    @PatchMapping("/api/conversation/group/{id}/title")
    public Result<ConversationResponse> updateTitle(@PathVariable("id") Long id,
                                                  @Valid @RequestBody UpdateGroupTitleRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(groupChatService.updateTitle(userId, id, request));
    }

    @MessageMapping("/group/{conversationId}/send")
    public void handleGroupMessage(
            @DestinationVariable Long conversationId,
            @Payload @Valid SendMessageRequest request,
            Principal principal) {
        long userId = Long.parseLong(principal.getName());
        groupChatService.handleGroupMessage(userId, conversationId, request);
    }
}
