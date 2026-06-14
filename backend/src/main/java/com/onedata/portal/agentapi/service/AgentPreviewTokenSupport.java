package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.config.AgentApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues and verifies one-time publish preview tokens.
 *
 * <p>The token binds a publish to a specific workflow version that was just
 * previewed, so deploy/online cannot run without a fresh preview (API-layer
 * "preview first" guard) — independent of the agent-side permission mode. The
 * token is an HMAC over {@code workflowId:versionId:expiry} keyed by the agent
 * service token, so it is stateless and survives across the request pair.
 */
@Component
@RequiredArgsConstructor
public class AgentPreviewTokenSupport {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String FALLBACK_SECRET = "odw-agent-preview-token-secret";

    private final AgentApiProperties agentApiProperties;

    @Value("${agent.api.preview-token-ttl-seconds:600}")
    private long ttlSeconds = 600;

    /** Issue a token for the workflow's current version. */
    public String issue(long workflowId, Long versionId) {
        long expiry = Instant.now().getEpochSecond() + Math.max(60, ttlSeconds);
        String payload = workflowId + ":" + (versionId == null ? "" : versionId) + ":" + expiry;
        String encodedPayload = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(payload));
        return encodedPayload + "." + sig;
    }

    /**
     * Verify a token against the workflow's current state. Throws if the token is
     * malformed, tampered, expired, for a different workflow, or for a version
     * that no longer matches the current one (i.e. the workflow changed since the
     * preview).
     */
    public void verify(long workflowId, Long currentVersionId, String token) {
        if (!StringUtils.hasText(token) || !token.contains(".")) {
            throw new IllegalArgumentException("发布预览凭证缺失或无效，请先调用发布预览");
        }
        String[] parts = token.split("\\.", 2);
        String payload;
        try {
            payload = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("发布预览凭证无效");
        }
        String expectedSig = B64.encodeToString(hmac(payload));
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("发布预览凭证签名校验失败");
        }
        String[] fields = payload.split(":", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("发布预览凭证格式错误");
        }
        long tokenWorkflowId;
        long expiry;
        try {
            tokenWorkflowId = Long.parseLong(fields[0]);
            expiry = Long.parseLong(fields[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("发布预览凭证格式错误");
        }
        if (tokenWorkflowId != workflowId) {
            throw new IllegalArgumentException("发布预览凭证与目标工作流不匹配");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw new IllegalArgumentException("发布预览凭证已过期，请重新预览");
        }
        String tokenVersion = fields[1];
        String currentVersion = currentVersionId == null ? "" : String.valueOf(currentVersionId);
        if (!tokenVersion.equals(currentVersion)) {
            throw new IllegalStateException("工作流自预览后已变更，请重新预览再发布");
        }
    }

    private byte[] hmac(String payload) {
        String secret = StringUtils.hasText(agentApiProperties.getServiceToken())
                ? agentApiProperties.getServiceToken()
                : FALLBACK_SECRET;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成发布预览凭证", e);
        }
    }
}
