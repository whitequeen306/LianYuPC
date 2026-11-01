package com.lianyu.service.relationship;

/**
 * 里程碑 key 的唯一事实来源。
 * 前端按 key 做 i18n，Python 不消费 —— 这里只放机器可读短词，不写文案。
 */
public final class RelationshipMilestones {

    public static final String FIRST_CONTACT = "FIRST_CONTACT";
    public static final String FIRST_VULNERABLE = "FIRST_VULNERABLE";
    public static final String FIRST_REPAIR = "FIRST_REPAIR";
    public static final String FIRST_NICKNAME = "FIRST_NICKNAME";
    public static final String FIRST_RITUAL = "FIRST_RITUAL";
    public static final String DAY_7 = "DAY_7";
    public static final String DAY_30 = "DAY_30";
    public static final String DAY_100 = "DAY_100";
    public static final String STREAK_3 = "STREAK_3";
    public static final String STREAK_7 = "STREAK_7";
    public static final String PHASE_FAMILIAR = "PHASE_FAMILIAR";
    public static final String PHASE_DEPENDENT = "PHASE_DEPENDENT";
    public static final String PHASE_STABLE_INTIMATE = "PHASE_STABLE_INTIMATE";

    private RelationshipMilestones() {
    }
}
