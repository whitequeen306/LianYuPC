package com.lianyu.service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** App 侧生成绑定码：指定要给哪个角色开一条 QQ 通道。 */
@Data
public class IssueQqBindCodeRequest {
    @NotNull
    private Long characterId;
}
