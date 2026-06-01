package com.lianyu.service.tools;

public record ToolFactBundle(
        String timeText,
        String weatherText,
        String city
) {
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        if (timeText != null && !timeText.isBlank()) {
            sb.append(timeText.trim());
        }
        if (weatherText != null && !weatherText.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(weatherText.trim());
        }
        return sb.toString();
    }
}
