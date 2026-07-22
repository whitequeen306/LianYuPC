package com.lianyu.service.tools;

import cn.hutool.core.util.StrUtil;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.support.OutputLanguageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 角色近 7 日日记 / 动态 / 评论摘要。按需调用，不再每轮预注入 system prompt。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecentActivityTool {

    private final CharacterRecentActivityService characterRecentActivityService;
    private final OutputLanguageService outputLanguageService;

    @Tool(name = "get_my_recent_life", description = """
            查询「我」（当前角色）最近的生活动态：近几天写的日记、发的朋友圈、评论摘要。
            当用户问你最近在干嘛、上次日记写了什么、有没有发动态、最近过得怎样时调用。
            日常寒暄、问天气/时间、不涉及近况时不要调用。""")
    public String getMyRecentLife() {
        ChatToolContext.Scope scope = ChatToolContext.require();
        String lang = outputLanguageService.resolveForRequest(scope.userId(), null);
        String block = characterRecentActivityService.formatForPrompt(
                scope.userId(), scope.characterId(), lang);
        if (StrUtil.isBlank(block)) {
            log.debug("get_my_recent_life empty: userId={}, characterId={}",
                    scope.userId(), scope.characterId());
            return "（最近几天没有日记或动态记录）";
        }
        log.info("get_my_recent_life hit: userId={}, characterId={}, chars={}",
                scope.userId(), scope.characterId(), block.length());
        return block;
    }
}
