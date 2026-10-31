package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.MomentsCommentMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CharacterRecentActivityServiceTest {

    private CharacterDiaryMapper characterDiaryMapper;
    private MomentsPostMapper momentsPostMapper;
    private MomentsCommentMapper momentsCommentMapper;
    private CharacterMapper characterMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private CharacterRecentActivityService service;

    @BeforeEach
    void setUp() {
        characterDiaryMapper = mock(CharacterDiaryMapper.class);
        momentsPostMapper = mock(MomentsPostMapper.class);
        momentsCommentMapper = mock(MomentsCommentMapper.class);
        characterMapper = mock(CharacterMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new CharacterRecentActivityService(
                characterDiaryMapper,
                momentsPostMapper,
                momentsCommentMapper,
                characterMapper,
                redisTemplate);
        // 文案归 Python：单测里用最小 stub 回放段头/收尾两段，避免把文案抄进 Java 测试
        org.springframework.test.util.ReflectionTestUtils.setField(service, "promptPort",
                new com.lianyu.service.agent.SceneInstructionPort() {
                    @Override
                    public String instruction(String scene, String outputLanguage) {
                        return null;
                    }

                    @Override
                    public String render(String name, java.util.Map<String, Object> variables,
                                         int leadingNewlines) {
                        if (name.startsWith("recent_life_title_")) {
                            return "=== 你最近的生活动态（背景记忆，勿逐条复述） ===";
                        }
                        if ("recent_life_closing".equals(name)) {
                            return "请勿逐条复述以上动态，仅作背景感知。";
                        }
                        return null;
                    }
                });
    }

    @Test
    void formatForPrompt_emptyDataReturnsEmptyString() {
        when(valueOps.get(any())).thenReturn(null);
        when(characterDiaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(momentsPostMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(momentsCommentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertEquals("", service.formatForPrompt(1L, 2L, "zh"));
    }

    @Test
    void formatForPrompt_includesDiaryLine() {
        when(valueOps.get(any())).thenReturn(null);
        CharacterDiary diary = new CharacterDiary();
        diary.setCreatedAt(LocalDateTime.of(2026, 6, 22, 10, 0));
        diary.setTitle("雨夜");
        diary.setContent("今天想了很多关于未来的事情，写下来当作提醒。");
        when(characterDiaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(diary));
        when(momentsPostMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(momentsCommentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        String block = service.formatForPrompt(1L, 2L, "zh");

        assertTrue(block.contains("=== 你最近的生活动态"));
        assertTrue(block.contains("写了日记"));
        assertTrue(block.contains("雨夜"));
    }

    @Test
    void formatForPrompt_withoutPromptPortDoesNotCacheFailure() {
        // 端口缺失时返回空串，但绝不能把「空块」缓存一小时，否则恢复后仍读不到动态
        org.springframework.test.util.ReflectionTestUtils.setField(service, "promptPort", null);
        when(valueOps.get(any())).thenReturn(null);

        assertEquals("", service.formatForPrompt(1L, 2L, "zh"));
        verify(valueOps, org.mockito.Mockito.never()).set(any(), any(), any());
    }

    @Test
    void evictCache_deletesRedisKey() {
        service.evictCache(5L, 6L);
        verify(redisTemplate).delete("activity:prompt:5:6");
    }
}
