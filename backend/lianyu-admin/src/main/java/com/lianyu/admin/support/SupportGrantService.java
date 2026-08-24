package com.lianyu.admin.support;

import com.lianyu.admin.audit.AdminAuditService;
import com.lianyu.admin.identity.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SupportGrantService {
    private final JdbcTemplate jdbc; private final AdminAuthorizationService authorization; private final AdminAuditService audit;
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    public String issue(long conversationId) { authorization.require("support.conversation.read"); byte[] bytes=new byte[24]; new SecureRandom().nextBytes(bytes); String code=HexFormat.of().formatHex(bytes); jdbc.update("INSERT INTO support_access_grant(conversation_id,issued_by,code_hash,expires_at) VALUES(?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE))",conversationId,authorization.currentAdminId(),hash(code)); audit.record("support.grant.issue","conversation",String.valueOf(conversationId),"success",Map.of("code","[REDACTED]")); return code; }
    public void redeem(long conversationId,String code) { authorization.require("support.conversation.read"); int updated=jdbc.update("UPDATE support_access_grant SET redeemed_by=?,redeemed_at=CURRENT_TIMESTAMP(3) WHERE conversation_id=? AND code_hash=? AND redeemed_at IS NULL AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(3)",authorization.currentAdminId(),conversationId,hash(code)); if(updated!=1) throw new IllegalArgumentException("支持授权码无效或已过期"); }
}
