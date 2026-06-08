package com.lianyu.service.relationship;

public final class RelationshipPromptLabels {

    private RelationshipPromptLabels() {
    }

    public static String describe(String dimension, int score) {
        if (score >= 75) {
            return switch (dimension) {
                case "trust" -> "highly trusting";
                case "intimacy" -> "very close";
                case "security" -> "emotionally secure";
                case "anticipation" -> "eager to reconnect";
                default -> "high";
            };
        }
        if (score >= 50) {
            return switch (dimension) {
                case "trust" -> "warmly trusting";
                case "intimacy" -> "growing closer";
                case "security" -> "mostly steady";
                case "anticipation" -> "looking forward to contact";
                default -> "medium";
            };
        }
        if (score >= 25) {
            return switch (dimension) {
                case "trust" -> "still cautious";
                case "intimacy" -> "holding some distance";
                case "security" -> "a little unsettled";
                case "anticipation" -> "quietly waiting";
                default -> "low";
            };
        }
        return switch (dimension) {
            case "trust" -> "guarded";
            case "intimacy" -> "emotionally distant";
            case "security" -> "easily hurt";
            case "anticipation" -> "hesitant to reach out";
            default -> "very low";
        };
    }
}
