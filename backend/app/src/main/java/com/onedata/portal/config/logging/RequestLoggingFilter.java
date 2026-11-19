package com.onedata.portal.config.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id (X-Request-ID, accepted or generated, and returned)
 * and logs one completion line. Outermost, so requests rejected by
 * authentication are logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-ID";
    static final String MDC_KEY = "request_id";
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final Logger log = LoggerFactory.getLogger("com.onedata.portal.request");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && VALID_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            if (!request.getRequestURI().startsWith(HEALTH_PATH)) {
                log.atInfo()
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("status_code", status)
                        .addKeyValue("duration_ms", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0)
                        .log("request completed");
            }
            MDC.remove(MDC_KEY);
        }
    }
}
