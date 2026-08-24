CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    totp_secret VARCHAR(512) NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME(3) NULL,
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_admin_user_username (username),
    KEY idx_admin_user_status (status)
);

CREATE TABLE IF NOT EXISTS admin_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_key VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    protected_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_admin_role_key (role_key)
);

CREATE TABLE IF NOT EXISTS admin_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    permission_key VARCHAR(128) NOT NULL,
    permission_name VARCHAR(160) NOT NULL,
    UNIQUE KEY uk_admin_permission_key (permission_key)
);

CREATE TABLE IF NOT EXISTS admin_user_role (
    admin_user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (admin_user_id, role_id),
    CONSTRAINT fk_admin_user_role_user FOREIGN KEY (admin_user_id) REFERENCES admin_user(id),
    CONSTRAINT fk_admin_user_role_role FOREIGN KEY (role_id) REFERENCES admin_role(id)
);

CREATE TABLE IF NOT EXISTS admin_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_admin_role_permission_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
    CONSTRAINT fk_admin_role_permission_permission FOREIGN KEY (permission_id) REFERENCES admin_permission(id)
);

CREATE TABLE IF NOT EXISTS admin_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_admin_session_token (token_hash),
    KEY idx_admin_session_user (admin_user_id, revoked_at),
    CONSTRAINT fk_admin_session_user FOREIGN KEY (admin_user_id) REFERENCES admin_user(id)
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id BIGINT NULL,
    action_key VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id VARCHAR(128) NULL,
    result VARCHAR(16) NOT NULL,
    detail_json JSON NULL,
    ip_address VARCHAR(64) NULL,
    trace_id VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_admin_audit_actor_time (actor_id, created_at),
    KEY idx_admin_audit_action_time (action_key, created_at)
);

CREATE TABLE IF NOT EXISTS app_release (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'draft',
    package_url VARCHAR(1024) NULL,
    sha512 CHAR(128) NULL,
    package_size BIGINT NULL,
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT NULL,
    created_by BIGINT NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_app_release_version_channel (version, channel),
    KEY idx_app_release_channel_state (channel, state, published_at)
);

CREATE TABLE IF NOT EXISTS announcement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'draft',
    published_at DATETIME(3) NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS release_rollout (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    release_id BIGINT NOT NULL,
    percentage DECIMAL(5,2) NOT NULL DEFAULT 100.00,
    salt VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_release_rollout_release FOREIGN KEY (release_id) REFERENCES app_release(id)
);

CREATE TABLE IF NOT EXISTS admin_config_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL,
    revision_no INT NOT NULL,
    value_json JSON NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_admin_config_revision (config_key, revision_no)
);

INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'admin.manage', '管理管理员') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='admin.manage');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'user.read', '查看用户') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='user.read');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'user.moderate', '管理用户状态') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='user.moderate');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'release.manage', '管理客户端版本') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='release.manage');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'announcement.manage', '管理公告') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='announcement.manage');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'audit.read', '查看审计日志') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='audit.read');
INSERT INTO admin_permission(permission_key, permission_name)
SELECT * FROM (SELECT 'system.health', '查看服务健康') x WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_key='system.health');

INSERT INTO admin_role(role_key, role_name, protected_role)
SELECT * FROM (SELECT 'super_admin', '超级管理员', TRUE) x WHERE NOT EXISTS (SELECT 1 FROM admin_role WHERE role_key='super_admin');
INSERT INTO admin_role(role_key, role_name, protected_role)
SELECT * FROM (SELECT 'operations', '运营管理员', FALSE) x WHERE NOT EXISTS (SELECT 1 FROM admin_role WHERE role_key='operations');

INSERT INTO admin_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM admin_role r CROSS JOIN admin_permission p
WHERE r.role_key='super_admin' AND NOT EXISTS (SELECT 1 FROM admin_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO admin_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM admin_role r JOIN admin_permission p ON p.permission_key IN ('user.read','release.manage','announcement.manage','system.health')
WHERE r.role_key='operations' AND NOT EXISTS (SELECT 1 FROM admin_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
