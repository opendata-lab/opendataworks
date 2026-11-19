package com.onedata.portal.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardJsonLogFormatterTest {

    private final StandardJsonLogFormatter formatter = new StandardJsonLogFormatter();
    private final Logger logger = new LoggerContext().getLogger("com.onedata.portal.Test");

    private JsonNode format(LoggingEvent event) throws Exception {
        String line = formatter.format(event);
        assertThat(line).endsWith("\n");
        assertThat(line.substring(0, line.length() - 1)).doesNotContain("\n");
        return new ObjectMapper().readTree(line);
    }

    @Test
    void writesTheStandardFieldsInUtc() throws Exception {
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.WARN, "hello {}\nworld", null, new Object[]{"there"});
        event.setTimeStamp(1_763_539_200_123L); // 2025-11-19T08:00:00.123Z
        event.setMDCPropertyMap(Map.of("request_id", "req-1", "unrelated", "x"));
        event.setKeyValuePairs(List.of(new KeyValuePair("status_code", 200), new KeyValuePair("other", 1)));

        JsonNode entry = format(event);

        assertThat(entry.get("timestamp").asText()).isEqualTo("2025-11-19T08:00:00.123Z");
        assertThat(entry.get("level").asText()).isEqualTo("WARN");
        assertThat(entry.get("service").asText()).isEqualTo("opendataworks-backend");
        assertThat(entry.get("logger").asText()).isEqualTo("com.onedata.portal.Test");
        assertThat(entry.get("message").asText()).isEqualTo("hello there\nworld");
        assertThat(entry.get("request_id").asText()).isEqualTo("req-1");
        assertThat(entry.get("status_code").asInt()).isEqualTo(200);
        assertThat(entry.has("unrelated")).isFalse();
        assertThat(entry.has("other")).isFalse();
    }

    @Test
    void keepsTheStackTraceInsideTheLineAndRedactsIt() throws Exception {
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "failed", new IllegalStateException("password=hunter2"), null);
        event.setMDCPropertyMap(Map.of()); // a bare test context has no MDC adapter
        JsonNode entry = format(event);
        assertThat(entry.get("stack_trace").asText()).contains("IllegalStateException").doesNotContain("hunter2");
    }

    @Test
    void redactsCredentialsAndCapsLength() {
        String text = StandardJsonLogFormatter.redact("Bearer abc.def token: t-1 jdbc:mysql://u:pw@db:3306/x"
                + " access_token=s1 client_secret=s2 {'password': 's3'} Authorization: Basic s4"
                + " https://dolphin.invalid/x?access_token=s5&y=1");
        assertThat(text).doesNotContain("abc.def").doesNotContain("t-1").doesNotContain(":pw@")
                .doesNotContain("s1").doesNotContain("s2").doesNotContain("s3").doesNotContain("s4").doesNotContain("s5");
        assertThat(StandardJsonLogFormatter.redact("max_tokens=4096")).isEqualTo("max_tokens=4096");
        assertThat(StandardJsonLogFormatter.redact("x".repeat(20_000)).length()).isLessThan(8_300);
    }
}
