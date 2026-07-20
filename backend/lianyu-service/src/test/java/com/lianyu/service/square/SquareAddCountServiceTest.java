package com.lianyu.service.square;

import static org.mockito.Mockito.verify;

import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class SquareAddCountServiceTest {

    @Mock private CharacterSquareTemplateMapper templateMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;

    private SquareAddCountService service;

    @BeforeEach
    void setUp() {
        service = new SquareAddCountService(templateMapper, stringRedisTemplate);
    }

    @Test
    void incrementAndInvalidate_updatesDbThenDeletesCache() {
        service.incrementAndInvalidate(12L);
        verify(templateMapper).incrementAddCount(12L);
        verify(stringRedisTemplate).delete(SquareAddCountService.REDIS_HASH_KEY);
    }
}
