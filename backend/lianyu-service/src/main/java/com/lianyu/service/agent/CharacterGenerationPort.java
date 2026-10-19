package com.lianyu.service.agent;

import com.lianyu.service.dto.VaultEntryResponse;

/**
 * 角色设定生成 / 抽取端口。
 *
 * <p>Java 只负责输入清洗、vault 解密与 JSON 解析；prompt 与模型调用在 Python。
 */
public interface CharacterGenerationPort {

    Result generate(long userId, VaultEntryResponse vault, String description);

    Result importDraft(long userId, VaultEntryResponse vault,
                       String preparedSource, String userAddressing);

    record Result(String content, String model) {
    }
}
