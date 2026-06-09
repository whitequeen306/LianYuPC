package com.lianyu.service.memory;

import com.lianyu.dao.enums.MemoryType;

public record ExtractedMemory(String summary, MemoryType memoryType, Long sourceMsgId, double importance) {}
