package com.lianyu.service.graph;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnNodes;
import com.lianyu.service.graph.nodes.AssembleMessagesNode;
import com.lianyu.service.graph.nodes.AssemblePromptNode;
import com.lianyu.service.graph.nodes.InvokeModelNode;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class ChatTurnGraphConfig {

    private final AssemblePromptNode assemblePromptNode;
    private final AssembleMessagesNode assembleMessagesNode;
    private final InvokeModelNode invokeModelNode;

    @Bean
    public CompiledGraph chatTurnGraph() throws GraphStateException {
        KeyStrategyFactory keyStrategyFactory = this::keyStrategies;
        StateGraph graph = new StateGraph("chatTurn", keyStrategyFactory)
                .addNode(ChatTurnNodes.ASSEMBLE_PROMPT, node_async(assemblePromptNode))
                .addNode(ChatTurnNodes.ASSEMBLE_MESSAGES, node_async(assembleMessagesNode))
                .addNode(ChatTurnNodes.INVOKE_MODEL, node_async(invokeModelNode))
                .addEdge(START, ChatTurnNodes.ASSEMBLE_PROMPT)
                .addEdge(ChatTurnNodes.ASSEMBLE_PROMPT, ChatTurnNodes.ASSEMBLE_MESSAGES)
                .addEdge(ChatTurnNodes.ASSEMBLE_MESSAGES, ChatTurnNodes.INVOKE_MODEL)
                .addEdge(ChatTurnNodes.INVOKE_MODEL, END);
        return graph.compile();
    }

    private Map<String, KeyStrategy> keyStrategies() {
        ReplaceStrategy replace = new ReplaceStrategy();
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put(ChatTurnKeys.USER_ID, replace);
        strategies.put(ChatTurnKeys.CHARACTER_ID, replace);
        strategies.put(ChatTurnKeys.CONVERSATION_ID, replace);
        strategies.put(ChatTurnKeys.RAW_USER_TEXT, replace);
        strategies.put(ChatTurnKeys.MODEL_USER_TEXT, replace);
        strategies.put(ChatTurnKeys.PROVIDER, replace);
        strategies.put(ChatTurnKeys.MODEL, replace);
        strategies.put(ChatTurnKeys.TEMPERATURE, replace);
        strategies.put(ChatTurnKeys.OUTPUT_LANGUAGE, replace);
        strategies.put(ChatTurnKeys.SCENE, replace);
        strategies.put(ChatTurnKeys.IMAGE_URL, replace);
        strategies.put(ChatTurnKeys.HISTORY_MESSAGES, replace);
        strategies.put(ChatTurnKeys.STREAMING, replace);
        strategies.put(ChatTurnKeys.STREAM_SINK, replace);
        strategies.put(ChatTurnKeys.CHARACTER_REF, replace);
        strategies.put(ChatTurnKeys.TOOL_CHARACTER_SETTINGS, replace);
        strategies.put(ChatTurnKeys.USER_CITY, replace);
        strategies.put(ChatTurnKeys.EXTRA_SYSTEM_SUFFIX, replace);
        strategies.put(ChatTurnKeys.CURRENT_USER_MSG_ID, replace);
        strategies.put(ChatTurnKeys.GROUP_EXTRAS, replace);
        strategies.put(ChatTurnKeys.PREPARED_MESSAGES, replace);
        strategies.put(ChatTurnKeys.MEMORY_BLOCK, replace);
        strategies.put(ChatTurnKeys.RELATIONSHIP_BLOCK, replace);
        strategies.put(ChatTurnKeys.SESSION_SUMMARY_BLOCK, replace);
        strategies.put(ChatTurnKeys.SYSTEM_PROMPT, replace);
        strategies.put(ChatTurnKeys.MESSAGE_DTOS, replace);
        strategies.put(ChatTurnKeys.ASSISTANT_RAW, replace);
        strategies.put(ChatTurnKeys.ASSISTANT_FINAL, replace);
        strategies.put(ChatTurnKeys.TOTAL_TOKENS, replace);
        strategies.put(ChatTurnKeys.PROMPT_TOKENS, replace);
        strategies.put(ChatTurnKeys.COMPLETION_TOKENS, replace);
        strategies.put(ChatTurnKeys.INVOKE_ERROR, replace);
        return strategies;
    }
}
