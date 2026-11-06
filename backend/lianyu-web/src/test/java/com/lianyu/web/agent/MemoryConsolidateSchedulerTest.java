package com.lianyu.web.agent;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemoryConsolidateSchedulerTest {

    @Mock
    private AgentRuntimeClient client;

    @Mock
    private AgentRuntimeProperties properties;

    @Test
    void sendsTheRequestOnlyWhenAgentIsActive() {
        when(client.properties()).thenReturn(properties);
        when(properties.agentActive()).thenReturn(true);
        when(client.postJson(eq("/agent/memory/consolidate"), eq(0L), anyMap()))
                .thenReturn("{\"written\":1}");

        new MemoryConsolidateScheduler(client).weeklyDigest();

        verify(client).postJson(eq("/agent/memory/consolidate"), eq(0L), anyMap());
    }

    @Test
    void staysQuietWhenAgentIsDisabled() {
        when(client.properties()).thenReturn(properties);
        when(properties.agentActive()).thenReturn(false);

        new MemoryConsolidateScheduler(client).weeklyDigest();

        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }
}
