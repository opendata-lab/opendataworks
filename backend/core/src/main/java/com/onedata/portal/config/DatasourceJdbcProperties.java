package com.onedata.portal.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Doris JDBC 连接可配置项，支持通过 application.yml 或环境变量覆写。 */
@Data
@Component
@ConfigurationProperties(prefix = "doris.jdbc")
public class DatasourceJdbcProperties {

    /** 未指定数据库时使用的默认库。 */
    private String defaultDatabase = "information_schema";

    /** 完整的 JDBC URL 模板，按顺序替换 FE 主机、端口、数据库。 */
    private String urlTemplate =
            "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useTimezone=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=300000";

    /** 会话字符集设置。 */
    private boolean sessionCharsetEnabled = true;

    private String sessionCharset = "utf8mb4";
    private String sessionCharsetFallback = "utf8";
}
