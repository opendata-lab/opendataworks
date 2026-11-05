package com.onedata.portal.security;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

@Data
@ConfigurationProperties("odw.http")
public class HttpSecurityProperties {
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean requirePrivateNetwork = true;

    /** Exact proxy addresses, never inferred from caller-supplied headers. */
    private List<String> trustedProxies = new ArrayList<>();
}
