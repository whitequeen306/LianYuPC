package com.lianyu.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConsumer {

    private final MemoryWriter memoryWriter;

    @RabbitListener(
            queues = "memory.summary.queue",
            concurrency = "${lianyu.mq.memory.listener-concurrency:2-8}"
    )
    public void onMemorySummaryTask(MemoryWriter.MemorySummaryTask task) {
        log.info("Received memory summary task: conversationId={}", task.conversationId());
        memoryWriter.processSummary(task);
    }
}
