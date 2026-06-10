package com.onedata.auth.jwt;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 默认 HS256 会话令牌实现
 * claims 契约: sub(用户ID) / username / role / auth_source / iss / iat / exp
 */
@Slf4j
public class Hs256JwtService implements JwtService {

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_AUTH_SOURCE = "auth_source";

    private final SecretKey key;
    private final String issuer;
    private final long ttlMillis;

    public Hs256JwtService(OdwAuthProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("odw.auth.jwt.secret 长度必须不少于32字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.getJwt().getIssuer();
        this.ttlMillis = properties.getJwt().getTtl().toMillis();
    }

    @Override
    public String issue(UserContext user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(user.getUserId())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole())
                .claim(CLAIM_AUTH_SOURCE, user.getAuthSource())
                .setIssuer(issuer)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Override
    public UserContext verify(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return new UserContext(
                    claims.getSubject(),
                    claims.get(CLAIM_USERNAME, String.class),
                    claims.get(CLAIM_ROLE, String.class),
                    claims.get(CLAIM_AUTH_SOURCE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Session token rejected: {}", e.getMessage());
            return null;
        }
    }
}
