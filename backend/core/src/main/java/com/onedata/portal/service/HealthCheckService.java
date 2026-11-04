package com.onedata.portal.service;

import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/** 服务健康检查服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final DolphinConfigService dolphinConfigService;
    private final DorisClusterMapper dorisClusterMapper;
    private final RestClient.Builder restClientBuilder;

    /** 检查所有服务健康状态 */
    public Map<String, ServiceHealthStatus> checkAllServices() {
        Map<String, ServiceHealthStatus> results = new HashMap<>();

        // 检查 DolphinScheduler OpenAPI
        results.put("dolphinscheduler", checkDolphinSchedulerApi());

        // 检查 Doris 集群
        results.put("doris-cluster", checkDorisCluster());

        return results;
    }

    /** 检查 DolphinScheduler OpenAPI 健康状态 */
    public ServiceHealthStatus checkDolphinSchedulerApi() {
        ServiceHealthStatus status = new ServiceHealthStatus();
        status.setServiceName("DolphinScheduler OpenAPI");

        DolphinConfig config = dolphinConfigService.getActiveConfig();
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            status.setHealthy(false);
            status.setMessage("DolphinScheduler config is missing or inactive");
            return status;
        }
        status.setServiceUrl(config.getUrl());

        try {
            long startTime = System.currentTimeMillis();

            // Check connectivity by querying projects
            RestClient client = healthClient(Duration.ofSeconds(5));
            String checkUrl = config.getUrl() + "/projects?pageSize=1";

            String response =
                    client.get()
                            .uri(checkUrl)
                            .header("token", config.getToken())
                            .retrieve()
                            .body(String.class);

            long responseTime = System.currentTimeMillis() - startTime;

            if (response != null && response.contains("\"code\":0")) {
                status.setHealthy(true);
                status.setMessage("Service Normal");
                status.setResponseTimeMs(responseTime);
            } else {
                status.setHealthy(false);
                status.setMessage("Service returned abnormal status");
                status.setResponseTimeMs(responseTime);
            }

        } catch (Exception e) {
            log.error("Failed to check DolphinScheduler service health", e);
            status.setHealthy(false);
            status.setMessage("Cannot connect to service: " + e.getMessage());
            status.setError(e.getClass().getSimpleName());
        }

        return status;
    }

    /** 检查 Doris 集群健康状态 */
    public ServiceHealthStatus checkDorisCluster() {
        ServiceHealthStatus status = new ServiceHealthStatus();
        status.setServiceName("Doris Cluster");

        try {
            // 获取默认集群
            DorisCluster cluster =
                    dorisClusterMapper.selectList(null).stream()
                            .filter(c -> c.getIsDefault() == 1)
                            .findFirst()
                            .orElse(null);

            if (cluster == null) {
                status.setHealthy(false);
                status.setMessage("未配置 Doris 集群");
                return status;
            }

            status.setServiceUrl(
                    String.format("http://%s:%d", cluster.getFeHost(), cluster.getFePort()));

            long startTime = System.currentTimeMillis();

            // 通过 HTTP API 检查 Doris FE 状态
            RestClient client = healthClient(Duration.ofSeconds(5));
            String healthUrl =
                    String.format(
                            "http://%s:%d/api/health", cluster.getFeHost(), cluster.getFePort());

            try {
                String response = client.get().uri(healthUrl).retrieve().body(String.class);

                long responseTime = System.currentTimeMillis() - startTime;

                if (response != null && !response.isEmpty()) {
                    status.setHealthy(true);
                    status.setMessage("Doris FE 运行正常");
                    status.setResponseTimeMs(responseTime);

                    // 获取更多详细信息
                    Map<String, Object> details = getDorisDetails(cluster);
                    status.setDetails(details);
                } else {
                    status.setHealthy(false);
                    status.setMessage("Doris FE 无响应");
                    status.setResponseTimeMs(responseTime);
                }

            } catch (Exception e) {
                log.error("Failed to connect to Doris cluster", e);
                status.setHealthy(false);
                status.setMessage("无法连接到 Doris: " + e.getMessage());
                status.setError(e.getClass().getSimpleName());
            }

        } catch (Exception e) {
            log.error("Failed to check Doris cluster health", e);
            status.setHealthy(false);
            status.setMessage("检查 Doris 集群时出错: " + e.getMessage());
            status.setError(e.getClass().getSimpleName());
        }

        return status;
    }

    /** 获取 Doris 集群详细信息（通过 SQL 查询） */
    private Map<String, Object> getDorisDetails(DorisCluster cluster) {
        Map<String, Object> details = new HashMap<>();

        try {
            // 这里可以通过 JDBC 连接 Doris 查询系统表获取详细信息
            // 由于没有直接的 JDBC 连接,这里返回基本信息
            details.put("cluster_name", cluster.getClusterName());
            details.put("fe_host", cluster.getFeHost());
            details.put("fe_port", cluster.getFePort());
            details.put("status", cluster.getStatus());

        } catch (Exception e) {
            log.warn("Failed to get Doris details", e);
        }

        return details;
    }

    /** 检查 Doris 节点资源使用情况 */
    public List<DorisNodeResourceStatus> checkDorisNodeResources() {
        List<DorisNodeResourceStatus> nodeStatuses = new ArrayList<>();

        try {
            // 获取默认集群
            DorisCluster cluster =
                    dorisClusterMapper.selectList(null).stream()
                            .filter(c -> c.getIsDefault() == 1)
                            .findFirst()
                            .orElse(null);

            if (cluster == null) {
                log.warn("No default Doris cluster found");
                return nodeStatuses;
            }

            // 通过 Doris REST API 获取 Backend 节点信息
            RestClient client = healthClient(Duration.ofSeconds(10));
            String backendUrl =
                    String.format(
                            "http://%s:%d/rest/v1/system?path=//backends",
                            cluster.getFeHost(), cluster.getFePort());

            try {
                Map<String, Object> response =
                        client.get()
                                .uri(backendUrl)
                                .headers(
                                        headers -> {
                                            String auth =
                                                    Base64.getEncoder()
                                                            .encodeToString(
                                                                    (cluster.getUsername()
                                                                                    + ":"
                                                                                    + cluster
                                                                                            .getPassword())
                                                                            .getBytes());
                                            headers.set("Authorization", "Basic " + auth);
                                        })
                                .retrieve()
                                .body(Map.class);

                if (response != null && response.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    if (data.containsKey("backends")) {
                        List<Map<String, Object>> backends =
                                (List<Map<String, Object>>) data.get("backends");

                        for (Map<String, Object> backend : backends) {
                            DorisNodeResourceStatus nodeStatus = parseBackendInfo(backend);
                            nodeStatuses.add(nodeStatus);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Failed to get Doris backend info via REST API", e);
            }

        } catch (Exception e) {
            log.error("Failed to check Doris node resources", e);
        }

        return nodeStatuses;
    }

    /** 解析 Backend 节点信息 */
    private DorisNodeResourceStatus parseBackendInfo(Map<String, Object> backend) {
        DorisNodeResourceStatus status = new DorisNodeResourceStatus();

        try {
            status.setNodeId(String.valueOf(backend.get("BackendId")));
            status.setHost(String.valueOf(backend.get("Host")));
            status.setPort(Integer.parseInt(String.valueOf(backend.get("HeartbeatPort"))));
            status.setAlive("true".equalsIgnoreCase(String.valueOf(backend.get("Alive"))));

            // 解析磁盘使用率
            String diskUsage = String.valueOf(backend.getOrDefault("DiskUsage", "0%"));
            status.setDiskUsagePercent(parseDiskUsage(diskUsage));

            // 解析内存使用
            String memLimit = String.valueOf(backend.getOrDefault("MemLimit", "0"));
            String memUsed = String.valueOf(backend.getOrDefault("MemUsed", "0"));
            status.setMemoryLimitBytes(parseMemorySize(memLimit));
            status.setMemoryUsedBytes(parseMemorySize(memUsed));

            if (status.getMemoryLimitBytes() > 0) {
                status.setMemoryUsagePercent(
                        (double) status.getMemoryUsedBytes() / status.getMemoryLimitBytes() * 100);
            }

            // 判断节点健康状态
            status.setHealthy(
                    status.isAlive()
                            && status.getDiskUsagePercent() < 90.0
                            && status.getMemoryUsagePercent() < 90.0);

        } catch (Exception e) {
            log.warn("Failed to parse backend info: {}", backend, e);
        }

        return status;
    }

    /** 解析磁盘使用率字符串 (例如: "75.5%") */
    private double parseDiskUsage(String diskUsage) {
        try {
            return Double.parseDouble(diskUsage.replace("%", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 解析内存大小字符串 (例如: "16.00 GB") */
    private long parseMemorySize(String memSize) {
        try {
            String[] parts = memSize.trim().split("\\s+");
            if (parts.length == 2) {
                double value = Double.parseDouble(parts[0]);
                String unit = parts[1].toUpperCase();

                switch (unit) {
                    case "GB":
                        return (long) (value * 1024 * 1024 * 1024);
                    case "MB":
                        return (long) (value * 1024 * 1024);
                    case "KB":
                        return (long) (value * 1024);
                    case "B":
                        return (long) value;
                    default:
                        return Long.parseLong(parts[0]);
                }
            }
            return Long.parseLong(memSize);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 服务健康状态 */
    @Data
    public static class ServiceHealthStatus {
        private String serviceName;
        private String serviceUrl;
        private boolean healthy;
        private String message;
        private Long responseTimeMs;
        private String error;
        private Map<String, Object> details;
    }

    /** Doris 节点资源状态 */
    @Data
    public static class DorisNodeResourceStatus {
        private String nodeId;
        private String host;
        private Integer port;
        private boolean alive;
        private boolean healthy;
        private double diskUsagePercent;
        private long memoryLimitBytes;
        private long memoryUsedBytes;
        private double memoryUsagePercent;
        private String message;
    }

    private RestClient healthClient(Duration timeout) {
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return restClientBuilder.clone().requestFactory(factory).build();
    }
}
