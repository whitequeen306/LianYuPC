package com.lianyu.service.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class CreateGroupConversationRequest {
    @NotEmpty
    @Size(min = 2, max = 4)
    private List<Long> characterIds;

    private String title;
}
