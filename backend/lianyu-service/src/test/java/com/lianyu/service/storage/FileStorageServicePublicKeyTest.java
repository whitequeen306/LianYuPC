package com.lianyu.service.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lianyu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class FileStorageServicePublicKeyTest {

    @Test
    void acceptsWechatChannelUpdateObjects() {
        assertDoesNotThrow(() -> FileStorageService.validateObjectKey("updates/wechat-channel-latest.yml"));
        assertDoesNotThrow(() -> FileStorageService.validateObjectKey("updates/WechatChannel-win-x64-0.1.0.zip"));
        assertDoesNotThrow(() -> FileStorageService.validateObjectKey("updates/agent-latest.yml"));
    }

    @Test
    void rejectsTraversalAndUnknownWechatNames() {
        assertThrows(BusinessException.class, () -> FileStorageService.validateObjectKey("updates/../secret"));
        assertThrows(BusinessException.class,
                () -> FileStorageService.validateObjectKey("updates/WechatChannel-win-x64-0.1.zip"));
        assertThrows(BusinessException.class,
                () -> FileStorageService.validateObjectKey("updates/wechat-channel-latest.yml.bak"));
    }
}
