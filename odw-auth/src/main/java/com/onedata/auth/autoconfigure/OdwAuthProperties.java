package com.onedata.auth.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 认证模块配置（前缀 odw.auth）
 */
@Data
@ConfigurationProperties(prefix = "odw.auth")
public class OdwAuthProperties {

    /**
     * 总开关，关闭后整个模块不装配
     */
    private boolean enabled = true;

    private Jwt jwt = new Jwt();

    private Session session = new Session();

    private Anonymous anonymous = new Anonymous();

    /**
     * 免认证路径白名单（Ant 风格，匹配去掉 context-path 之后的 servlet 路径）
     * 例如 context-path=/api 时，/auth/login 对应外部 /api/auth/login
     */
    private List<String> whitelist = new ArrayList<>(Arrays.asList(
            "/auth/**",
            "/v1/health",
            "/v1/ai/**",
            "/actuator/health",
            "/error"
    ));

    @Data
    public static class Jwt {

        /**
         * HS256 共享密钥，长度至少32字符；生产通过 AUTH_JWT_SECRET 注入
         */
        private String secret = "opendataworks-dev-jwt-secret-change-me";

        /**
         * 签发方
         */
        private String issuer = "opendataworks";

        /**
         * 会话有效期
         */
        private Duration ttl = Duration.ofHours(8);
    }

    @Data
    public static class Session {

        /**
         * 会话 Cookie 名称
         */
        private String cookieName = "odw_session";

        /**
         * SameSite 属性
         */
        private String sameSite = "Lax";

        /**
         * 是否仅 HTTPS 下发 Cookie（生产开启）
         */
        private boolean secure = false;
    }

    @Data
    public static class Anonymous {

        /**
         * 会话缺失时是否回退为匿名用户（替代旧 auth.anonymous.*，作为临时回滚开关）
         */
        private boolean enabled = false;

        /**
         * 匿名用户ID
         */
        private String userId = "1";

        /**
         * 匿名用户名
         */
        private String username = "admin";
    }
}
