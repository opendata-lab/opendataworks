package com.onedata.portal.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;
import org.springframework.util.StringUtils;



/**
 * JSON 规范化与内容哈希工具。
 *
 * 提供与字段顺序无关的稳定序列化（对象按键名排序、递归处理），以及对应的
 * SHA-256 文本哈希，用于快照内容比对（是否需要创建新版本等）。
 *
 * <p>原先 {@code WorkflowService} 与 {@code TableMetadataVersionService} 各自重复了
 * 相同实现，这里统一为单一来源。方法为纯函数，便于单测，行为与原实现保持一致：
 * {@link #sha256(String)} 对空白输入返回 {@code null}（规范化结果不会为空白，故对原有
 * 调用方均不改变行为）。
 */
public final class JsonCanonicalizer {

    private JsonCanonicalizer() {
    }

    /**
     * 将 JSON 节点规范化为与字段顺序无关的稳定字符串表示。
     *
     * @param node JSON 节点，可为 {@code null}
     * @return 规范化字符串；空/缺失节点返回 {@code "null"}
     */
    public static String canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(fieldName).append('"').append(':');
                sb.append(canonicalize(node.get(fieldName)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalize(node.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return node.toString();
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要。
     *
     * @param text 输入文本
     * @return 小写十六进制摘要；输入为空白时返回 {@code null}
     */
    public static String sha256(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成 hash", e);
        }
    }
}
