package com.onedata.auth.jwt;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.UserContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Hs256JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";

    private OdwAuthProperties properties(String secret, Duration ttl) {
        OdwAuthProperties properties = new OdwAuthProperties();
        properties.getJwt().setSecret(secret);
        properties.getJwt().setTtl(ttl);
        return properties;
    }

    @Test
    void issueAndVerifyRoundTrip() {
        Hs256JwtService service = new Hs256JwtService(properties(SECRET, Duration.ofHours(1)));
        UserContext user = new UserContext("1", "admin", "admin", "local");

        String token = service.issue(user);
        assertNotNull(token);

        UserContext parsed = service.verify(token);
        assertNotNull(parsed);
        assertEquals("1", parsed.getUserId());
        assertEquals("admin", parsed.getUsername());
        assertEquals("admin", parsed.getRole());
        assertEquals("local", parsed.getAuthSource());
    }

    @Test
    void expiredTokenRejected() {
        Hs256JwtService service = new Hs256JwtService(properties(SECRET, Duration.ofMillis(-1000)));
        String token = service.issue(new UserContext("1", "admin", "admin", "local"));

        assertNull(service.verify(token));
    }

    @Test
    void tamperedTokenRejected() {
        Hs256JwtService service = new Hs256JwtService(properties(SECRET, Duration.ofHours(1)));
        String token = service.issue(new UserContext("1", "admin", "admin", "local"));

        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertNull(service.verify(tampered));
    }

    @Test
    void wrongSecretRejected() {
        Hs256JwtService issuer = new Hs256JwtService(properties(SECRET, Duration.ofHours(1)));
        Hs256JwtService verifier = new Hs256JwtService(properties(SECRET + "-other", Duration.ofHours(1)));

        String token = issuer.issue(new UserContext("1", "admin", "admin", "local"));
        assertNull(verifier.verify(token));
    }

    @Test
    void wrongIssuerRejected() {
        OdwAuthProperties other = properties(SECRET, Duration.ofHours(1));
        other.getJwt().setIssuer("someone-else");
        Hs256JwtService issuer = new Hs256JwtService(other);
        Hs256JwtService verifier = new Hs256JwtService(properties(SECRET, Duration.ofHours(1)));

        String token = issuer.issue(new UserContext("1", "admin", "admin", "local"));
        assertNull(verifier.verify(token));
    }

    @Test
    void shortSecretRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new Hs256JwtService(properties("too-short", Duration.ofHours(1))));
    }
}
