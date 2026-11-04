package com.onedata.portal.service;

import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

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
public class ServiceAccountTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final com.onedata.portal.mapper.ServiceAccountTokenMapper tokens;
    private final com.onedata.portal.mapper.PlatformUserOperationMapper operations;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final SysUserMapper users;

    @Data
    public static class TokenRequest {
        private String name;
        private Integer expiresInDays;
    }

    public java.util.List<com.onedata.portal.entity.ServiceAccountToken> list(Long userId) {
        requireService(userId, false);
        return tokens.listByUser(userId);
    }

    @Transactional
    public Map<String, Object> create(Long userId, TokenRequest input) {
        // Serialize issuance with account enable/delete changes, not just token writes.
        users.lockUserById(userId);
        requireService(userId, true);
        if (!StringUtils.hasText(input.getName()) || input.getName().trim().length() > 100)
            throw new IllegalArgumentException("Token名称不能为空且最多100字");
        int days = input.getExpiresInDays() == null ? 90 : input.getExpiresInDays();
        if (days < 1 || days > 365) throw new IllegalArgumentException("Token有效期为1～365天");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = "odw_sa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest = hash(raw), prefix = raw.substring(0, 16);
        var entity = new com.onedata.portal.entity.ServiceAccountToken();
        entity.setUserId(userId);
        entity.setName(input.getName().trim());
        entity.setTokenHash(digest);
        entity.setTokenPrefix(prefix);
        entity.setExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(days));
        entity.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        entity.setCreatedBy(operator());
        tokens.insert(entity);
        Map<String, Object> result =
                mapper.convertValue(
                        entity,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        audit(userId, "CREATE_SERVICE_TOKEN", "tokenId=" + result.get("id"));
        result.put("token", raw); // Only the creation response contains plaintext.
        return result;
    }

    @Transactional
    public void revoke(Long userId, Long tokenId) {
        requireService(userId, false);
        var entity = tokens.selectById(tokenId);
        if (entity == null || !userId.equals(entity.getUserId()))
            throw new IllegalArgumentException("Token不存在或不属于当前服务账号");
        tokens.revoke(userId, tokenId);
        audit(userId, "REVOKE_SERVICE_TOKEN", "tokenId=" + tokenId);
    }

    public String authenticate(String token) {
        if (token == null || !token.matches("odw_sa_[A-Za-z0-9_-]{43}")) throw invalid();
        String digest = hash(token);
        Long id = tokens.authenticate(digest);
        if (id == null) throw invalid();
        tokens.touchLastUsedAt(digest);
        return String.valueOf(id);
    }

    private SysUser requireService(Long id, boolean enabled) {
        SysUser user = users.selectById(id);
        if (user == null || !user.isServiceAccount())
            throw new IllegalArgumentException("仅服务账号可以管理Token");
        if (enabled && !Boolean.TRUE.equals(user.getEnabled()))
            throw new IllegalArgumentException("服务账号已停用，不能创建Token");
        return user;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("服务账号Token无效、已过期或账号已停用");
    }

    static String hash(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    private String operator() {
        String id = PrincipalContextHolder.getCurrentUserId();
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("需要登录管理员");
        return id;
    }

    private void audit(Long id, String action, String detail) {
        var operation = new com.onedata.portal.entity.PlatformUserOperation();
        operation.setOperatorId(operator());
        operation.setUserId(id);
        operation.setAction(action);
        operation.setDetail(detail);
        operations.insert(operation);
    }
}
