package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AnalyzeCharacterImportRequest {

    @NotBlank(message = "请提供人设或聊天记录")
    @Size(max = 100000, message = "人设或聊天记录过长，请截取后再导入")
    private String sourceText;
}
