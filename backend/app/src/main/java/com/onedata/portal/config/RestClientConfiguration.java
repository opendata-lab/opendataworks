package com.onedata.portal.config;

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
        return RestClient.builder().requestFactory(factory);
    }
}
