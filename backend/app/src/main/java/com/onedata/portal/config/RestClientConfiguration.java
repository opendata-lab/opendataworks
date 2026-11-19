package com.onedata.portal.config;

import com.onedata.portal.config.logging.RequestLoggingFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfiguration {
    @Bean
    RestClient.Builder dolphinRestClientBuilder() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .requestFactory(factory)
                // Carry the caller's request id to DolphinScheduler; read per
                // request, never baked into a cached client's default headers.
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestLoggingFilter.MDC_KEY);
                    if (requestId != null) request.getHeaders().set(RequestLoggingFilter.HEADER, requestId);
                    return execution.execute(request, body);
                });
    }
}
