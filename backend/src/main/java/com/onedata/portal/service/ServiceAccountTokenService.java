package com.onedata.portal.service;

import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.agentapi.auth.ServiceAccountAuthenticator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ServiceAccountTokenService implements ServiceAccountAuthenticator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FIELDS = "id,name,token_prefix AS prefix,DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%sZ') AS expiresAt,DATE_FORMAT(revoked_at,'%Y-%m-%dT%H:%i:%sZ') AS revokedAt,DATE_FORMAT(last_used_at,'%Y-%m-%dT%H:%i:%sZ') AS lastUsedAt,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%sZ') AS createdAt";
    private final JdbcTemplate jdbc;
    private final SysUserMapper users;

    @Data public static class TokenRequest { private String name; private Integer expiresInDays; }

    public List<Map<String,Object>> list(Long userId) {
        requireService(userId, false);
        return jdbc.queryForList("SELECT "+FIELDS+" FROM service_account_token WHERE user_id=? ORDER BY id DESC", userId);
    }

    @Transactional
    public Map<String,Object> create(Long userId, TokenRequest input) {
        // Serialize issuance with account enable/delete changes, not just token writes.
        jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE", userId);
        requireService(userId, true);
        if (!StringUtils.hasText(input.getName()) || input.getName().trim().length()>100)
            throw new IllegalArgumentException("Token名称不能为空且最多100字");
        int days=input.getExpiresInDays()==null?90:input.getExpiresInDays();
        if (days<1 || days>365) throw new IllegalArgumentException("Token有效期为1～365天");
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        String raw="odw_sa_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest=hash(raw),prefix=raw.substring(0,16);
        jdbc.update("INSERT INTO service_account_token(user_id,name,token_hash,token_prefix,expires_at,created_by,created_at) VALUES(?,?,?,?,DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? DAY),?,UTC_TIMESTAMP())",
                userId,input.getName().trim(),digest,prefix,days,operator());
        Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("SELECT "+FIELDS+" FROM service_account_token WHERE token_hash=?",digest));
        audit(userId,"CREATE_SERVICE_TOKEN","tokenId="+result.get("id"));
        result.put("token",raw); // Only the creation response contains plaintext.
        return result;
    }

    @Transactional
    public void revoke(Long userId, Long tokenId) {
        requireService(userId, false);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id FROM service_account_token WHERE id=? AND user_id=?",tokenId,userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Token不存在或不属于当前服务账号");
        jdbc.update("UPDATE service_account_token SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP()) WHERE id=? AND user_id=?",tokenId,userId);
        audit(userId,"REVOKE_SERVICE_TOKEN","tokenId="+tokenId);
    }

    @Override public String authenticate(String token) {
        if (token==null || !token.matches("odw_sa_[A-Za-z0-9_-]{43}")) throw invalid();
        String digest=hash(token);
        List<Long> ids=jdbc.queryForList("SELECT t.user_id FROM service_account_token t JOIN sys_user u ON u.id=t.user_id "
                +"WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>UTC_TIMESTAMP() "
                +"AND u.account_type='SERVICE' AND u.auth_source='service' AND u.enabled=1 AND u.deleted=0",Long.class,digest);
        if (ids.isEmpty()) throw invalid();
        jdbc.update("UPDATE service_account_token t JOIN sys_user u ON u.id=t.user_id SET t.last_used_at=UTC_TIMESTAMP() "
                +"WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>UTC_TIMESTAMP() "
                +"AND u.account_type='SERVICE' AND u.auth_source='service' AND u.enabled=1 AND u.deleted=0",digest);
        // Connector affected-row semantics may report 0 when timestamp already equals this second.
        // The SELECT is authoritative for this request; revocation takes effect on subsequent requests.
        return String.valueOf(ids.get(0));
    }

    private SysUser requireService(Long id, boolean enabled) {
        SysUser user=users.selectById(id);
        if(user==null || !user.isServiceAccount()) throw new IllegalArgumentException("仅服务账号可以管理Token");
        if(enabled && !Boolean.TRUE.equals(user.getEnabled())) throw new IllegalArgumentException("服务账号已停用，不能创建Token");
        return user;
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("服务账号Token无效、已过期或账号已停用");}
    static String hash(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(64);for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256不可用",e);}
    }
    private String operator() {String id=UserContextHolder.getCurrentUserId();if(!StringUtils.hasText(id))throw new IllegalArgumentException("需要登录管理员");return id;}
    private void audit(Long id,String action,String detail) {jdbc.update("INSERT INTO platform_user_operation(operator_id,user_id,action,detail) VALUES(?,?,?,?)",operator(),id,action,detail);}
}
