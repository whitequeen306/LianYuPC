package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ObserveDesktopRequest {

    @NotBlank(message = "缺少截图数据")
    @Size(max = 3_000_000, message = "截图数据过大")
    private String imageBase64;

    @Size(max = 500, message = "窗口标题过长")
    private String windowTitle;

    @Size(max = 4000, message = "人设内容过长")
    private String persona;
}
