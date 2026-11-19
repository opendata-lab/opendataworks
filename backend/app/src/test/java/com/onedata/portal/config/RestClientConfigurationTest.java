package com.onedata.portal.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientConfigurationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void dolphinCallsCarryTheCurrentRequestId() {
        RestClient.Builder builder = new RestClientConfiguration().dolphinRestClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://dolphin.invalid/a")).andExpect(header("X-Request-ID", "req-9")).andRespond(withSuccess());
        server.expect(requestTo("http://dolphin.invalid/b")).andExpect(headerDoesNotExist("X-Request-ID")).andRespond(withSuccess());

        MDC.put("request_id", "req-9");
        client.get().uri("http://dolphin.invalid/a").retrieve().toBodilessEntity();
        MDC.remove("request_id");
        client.get().uri("http://dolphin.invalid/b").retrieve().toBodilessEntity();
        server.verify();
    }
}
