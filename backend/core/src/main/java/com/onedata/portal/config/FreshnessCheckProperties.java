package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 数据新鲜度检查配置。检查绑定到工作流运行（事件驱动），不设墙钟轮询。
 */
@Data
@Component
@ConfigurationProperties(prefix = "freshness.check")
public class FreshnessCheckProperties {

    /** 工作流完成后触发新鲜度检查的开关。关闭后按需接口仍可用。 */
    private boolean enabled = true;

    /** 单次取数的 JDBC 查询超时（秒）。 */
    private int queryTimeoutSeconds = 30;

    /** 每个数据源的并发检查上限。 */
    private int maxConcurrentPerCluster = 4;
}
