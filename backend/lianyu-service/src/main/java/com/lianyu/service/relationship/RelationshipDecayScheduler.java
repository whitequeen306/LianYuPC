package com.lianyu.service.relationship;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关系时间衰减：与 EmotionDecayScheduler 同构。
 * 把漂移**物化进库**，Python 只读同一张表，避免 Java / Python 各算一遍造成 prompt 与 UI 不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationshipDecayScheduler {

    private final RelationshipStateService relationshipStateService;

    @Value("${lianyu.relationship.decay.enabled:true}")
    private boolean decayEnabled;

    @Scheduled(fixedDelayString = "${lianyu.relationship.decay.interval-ms:1800000}")
    public void decayRelationships() {
        if (!decayEnabled) {
            return;
        }
        try {
            int count = relationshipStateService.decayAll();
            if (count > 0) {
                log.debug("Relationship decay: {} states updated", count);
            }
        } catch (Exception e) {
            log.warn("Relationship decay tick failed: {}", e.getMessage());
        }
    }
}
