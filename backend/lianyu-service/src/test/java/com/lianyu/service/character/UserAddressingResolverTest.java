package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserAddressingResolverTest {

    @Test
    void resolve_prefersLongTermMemoryName() {
        String memory = "[用户画像]\n- 【长期记忆/姓名】小明\n- 【长期记忆/爱好】夜跑";
        assertEquals("小明", UserAddressingResolver.resolve(memory, "默认昵称"));
    }

    @Test
    void resolve_fallsBackToNickname() {
        assertEquals("默认昵称", UserAddressingResolver.resolve(null, "默认昵称"));
    }

    @Test
    void resolve_fallsBackToGenericYou() {
        assertEquals("你", UserAddressingResolver.resolve(null, null));
    }
}
