package com.onedata.portal.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One-line JSON in the shape shared with OntoFoundry and DataAgent
 * (docs/design/2026-11-19-logging-standard-design.md).
 *
 * <p>Spring Boot's built-in logstash format would do except for the timestamp:
 * it follows the JVM zone, which is Asia/Shanghai in the image, while the
 * standard is UTC. Changing the JVM zone would change application dates, so the
 * format is written here instead. Selected by {@code logging.structured.format.*}.
 */
public class StandardJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    static final String SERVICE = "opendataworks-backend";
    static final int MAX_FIELD_CHARS = 8192;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** MDC keys and key-value pairs copied into the line; anything else is left out. */
    private static final Set<String> FIELDS = Set.of(
            "request_id", "task_id", "run_id", "topic_id", "error_code",
            "method", "path", "status_code", "duration_ms");
    private static final List<Map.Entry<Pattern, String>> SECRETS = List.of(
            Map.entry(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9_.\\-+/=]+"), "$1 ***"),
            Map.entry(Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key)(\"?\\s*[:=]\\s*\"?)[^\\s\"',;&]+"), "$1$2***"),
            Map.entry(Pattern.compile("(://[^/\\s:@]+):[^/\\s@]+@"), "$1:***@"));

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", TIMESTAMP.format(Instant.ofEpochMilli(event.getTimeStamp())));
        entry.put("level", event.getLevel().toString());
        entry.put("service", SERVICE);
        entry.put("logger", event.getLoggerName());
        entry.put("message", redact(event.getFormattedMessage()));
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            mdc.forEach((key, value) -> {
                if (FIELDS.contains(key) && value != null) entry.put(key, value);
            });
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                if (FIELDS.contains(pair.key) && pair.value != null) entry.put(pair.key, pair.value);
            }
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) entry.put("stack_trace", redact(ThrowableProxyUtil.asString(thrown)));
        try {
            return mapper.writeValueAsString(entry) + "\n";
        } catch (JsonProcessingException e) {
            return "{\"level\":\"ERROR\",\"service\":\"" + SERVICE + "\",\"message\":\"log line could not be encoded\"}\n";
        }
    }

    static String redact(String text) {
        if (text == null) return "";
        for (Map.Entry<Pattern, String> secret : SECRETS) {
            text = secret.getKey().matcher(text).replaceAll(secret.getValue());
        }
        return text.length() <= MAX_FIELD_CHARS ? text : text.substring(0, MAX_FIELD_CHARS) + "…(truncated)";
    }
}
