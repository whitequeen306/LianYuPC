package com.lianyu.admin.identity;

import java.util.Set;

public final class AdminPermissionCatalog {
    public static final String SUPER_ADMIN = "super_admin";
    public static final Set<String> ALL = Set.of("admin.manage", "user.read", "user.moderate", "release.manage", "announcement.manage", "audit.read", "system.health");
    private AdminPermissionCatalog() {}
}
