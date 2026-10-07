package com.lianyu.service.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 角色即兴写日记工具：聊天中角色自己觉得今天的交流有趣/触动，主动调用入队一篇日记。
 * 只负责入队（复用 RabbitMQ ai.background 管道），正文由 {@link com.lianyu.service.character.CharacterDiaryService}
 * 异步后台生成，不阻塞聊天回合；生成后照常推 DIARY_NEW 通知。
 * <p>
 * 即兴日记与定时算法的「每天一篇」互斥各自独立（source=IMPROMPTU 只数 IMPROMPTU）；
 * 受 {@code lianyu.diary.impromptu-max-per-day} 配额约束，防模型每轮都调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteDiaryTool {

    private final ApiKeyVaultService apiKeyVaultService;
    private final CharacterDiaryMapper diaryMapper;
    private final AiBackgroundPublisher aiBackgroundPublisher;

    @Value("${lianyu.diary.impromptu-max-per-day:1}")
    private int impromptuMaxPerDay;

    @Tool(name = "write_diary", description = """
            把今天与对方的交流写进你自己的日记（异步后台完成，你只需表达意愿）。
            当你真心觉得今天聊到的内容有趣、触动你、值得私下记下来时调用；每天至多一次。
            用户直接要求你写日记时也可以调用。寒暄、问答、查资料等平淡场景不要调用；
            调用后不要向用户复述或预告日记内容。""")
    public String writeDiary() {
        ChatToolContext.Scope scope = ChatToolContext.require();
        Long userId = scope.userId();
        Long characterId = scope.characterId();
        if (userId == null || characterId == null) {
            return "（上下文缺失，无法写日记；请自然继续对话。）";
        }
        // 预检：用户没有文本模型就不承诺（后台生成走用户自己的 vault）
        if (apiKeyVaultService.resolvePreferredUserVault(userId) == null) {
            log.debug("write_diary skipped: no user text model, userId={}, characterId={}", userId, characterId);
            return "（你暂时没有能写日记的条件（用户未配置文本模型），请勿向用户承诺会写，自然继续对话。）";
        }
        if (countImpromptuToday(userId, characterId) >= Math.max(1, impromptuMaxPerDay)) {
            log.debug("write_diary skipped: impromptu quota reached, userId={}, characterId={}", userId, characterId);
            return "（今天已经写过日记了，不必再写；请自然继续对话。）";
        }
        try {
            aiBackgroundPublisher.publish(AiBackgroundTask.characterDiaryImpromptu(userId, characterId));
        } catch (Exception e) {
            log.warn("write_diary enqueue failed: userId={}, characterId={}, reason={}",
                    userId, characterId, e.getMessage());
            return "（此刻没能记下来；请自然继续对话，不要向用户提及失败。）";
        }
        log.info("write_diary enqueued: userId={}, characterId={}", userId, characterId);
        return "（你已在心里决定：把今天的点滴写进日记，稍后独自整理。请自然继续对话，不必向用户复述。）";
    }

    private long countImpromptuToday(Long userId, Long characterId) {
        Long count = diaryMapper.selectCount(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getCharacterId, characterId)
                .eq(CharacterDiary::getUserId, userId)
                .eq(CharacterDiary::getSource, CharacterDiary.SOURCE_IMPROMPTU)
                .ge(CharacterDiary::getCreatedAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0L : count;
    }
}
