package com.onedata.portal.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerRoutingTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new Routes())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void missingRouteStays404() throws Exception {
        mvc.perform(get("/retired"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void missingStaticResourceStays404() throws Exception {
        mvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void unsupportedMethodKeeps405AndAllowHeader() throws Exception {
        mvc.perform(post("/current"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.code").value(405));
    }

    @RestController
    static class Routes {
        @GetMapping("/current")
        String current() {
            return "ok";
        }

        @GetMapping("/missing-resource")
        String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-resource");
        }
    }
}
