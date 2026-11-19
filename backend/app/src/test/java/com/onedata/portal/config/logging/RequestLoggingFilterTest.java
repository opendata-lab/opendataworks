package com.onedata.portal.config.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger requestLogger = (Logger) LoggerFactory.getLogger("com.onedata.portal.request");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        requestLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        requestLogger.detachAppender(appender);
    }

    private MockHttpServletResponse run(String uri, String requestId, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (requestId != null) request.addHeader("X-Request-ID", requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get("request_id"));
                ((MockHttpServletResponse) res).setStatus(404);
            }
        });
        return response;
    }

    @Test
    void passesAValidIdThroughAndLogsOneCompletionLine() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = run("/api/v1/tables", "of-req-7", seen);

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("of-req-7");
        assertThat(seen.get()).isEqualTo("of-req-7");
        assertThat(MDC.get("request_id")).isNull();
        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getMDCPropertyMap()).containsEntry("request_id", "of-req-7");
        assertThat(line.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                .contains("method=GET", "path=/api/v1/tables", "status_code=404");
    }

    @Test
    void replacesAnInvalidIdAndSkipsHealthChecks() throws Exception {
        MockHttpServletResponse replaced = run("/api/v1/tables", "bad id!", new AtomicReference<>());
        assertThat(replaced.getHeader("X-Request-ID")).matches("[0-9a-f]{32}");

        run("/api/v1/health", null, new AtomicReference<>());
        assertThat(appender.list).hasSize(1);
    }
}
