package com.lianyu.admin.identity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminIdentityProperties.class)
public class AdminBootstrapService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AdminIdentityProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapIfConfigured() {
        if (properties.getUsername() == null || properties.getUsername().isBlank() || properties.getPassword() == null || properties.getPassword().isBlank()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user u JOIN admin_user_role ur ON ur.admin_user_id=u.id JOIN admin_role r ON r.id=ur.role_id WHERE r.role_key='super_admin' AND u.status='active'", Integer.class);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO admin_user(username,display_name,password_hash) VALUES(?,?,?)", properties.getUsername().trim(), properties.getUsername().trim(), passwordEncoder.encode(properties.getPassword()));
        Long userId = jdbc.queryForObject("SELECT id FROM admin_user WHERE username=?", Long.class, properties.getUsername().trim());
        Long roleId = jdbc.queryForObject("SELECT id FROM admin_role WHERE role_key='super_admin'", Long.class);
        jdbc.update("INSERT INTO admin_user_role(admin_user_id,role_id) VALUES(?,?)", userId, roleId);
        org.slf4j.LoggerFactory.getLogger(AdminBootstrapService.class).info("Initial admin bootstrap completed for configured username");
    }
}
