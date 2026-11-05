package com.onedata.auth.autoconfigure;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 认证模块配置（前缀 odw.auth） */
@Data
@ConfigurationProperties(prefix = "odw.auth")
public class OdwAuthProperties {

    private Jwt jwt = new Jwt();
    private Session session = new Session();

    @Data
    public static class Jwt {

        /** HS256 共享密钥，长度至少32字符；生产通过 AUTH_JWT_SECRET 注入 */
        private String secret = "opendataworks-dev-jwt-secret-change-me";

        /** 签发方 */
        private String issuer = "opendataworks";

        /** 会话有效期 */
        private Duration ttl = Duration.ofHours(8);
    }

    @Data
    public static class Session {

        /** 会话 Cookie 名称 */
        private String cookieName = "odw_session";

        /** SameSite 属性 */
        private String sameSite = "Lax";

        /** 是否仅 HTTPS 下发 Cookie（生产开启） */
        private boolean secure = false;
    }
}
