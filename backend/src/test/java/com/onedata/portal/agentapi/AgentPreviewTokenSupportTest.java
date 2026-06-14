package com.onedata.portal.agentapi;

import com.onedata.portal.agentapi.config.AgentApiProperties;
import com.onedata.portal.agentapi.service.AgentPreviewTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPreviewTokenSupportTest {

    private AgentPreviewTokenSupport support;

    @BeforeEach
    void setUp() {
        AgentApiProperties props = new AgentApiProperties();
        props.setServiceToken("unit-secret");
        support = new AgentPreviewTokenSupport(props);
        ReflectionTestUtils.setField(support, "ttlSeconds", 600L);
    }

    @Test
    void issuedTokenVerifiesForSameWorkflowAndVersion() {
        String token = support.issue(12L, 5L);
        assertDoesNotThrow(() -> support.verify(12L, 5L, token));
    }

    @Test
    void verifyRejectsMissingOrMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> support.verify(12L, 5L, null));
        assertThrows(IllegalArgumentException.class, () -> support.verify(12L, 5L, "not-a-token"));
    }

    @Test
    void verifyRejectsTamperedSignature() {
        String token = support.issue(12L, 5L);
        String tampered = token.substring(0, token.indexOf('.') + 1) + "AAAA";
        assertThrows(IllegalArgumentException.class, () -> support.verify(12L, 5L, tampered));
    }

    @Test
    void verifyRejectsDifferentWorkflow() {
        String token = support.issue(12L, 5L);
        assertThrows(IllegalArgumentException.class, () -> support.verify(99L, 5L, token));
    }

    @Test
    void verifyRejectsChangedVersion() {
        // Workflow changed since preview -> version drift -> reject (must re-preview).
        String token = support.issue(12L, 5L);
        assertThrows(IllegalStateException.class, () -> support.verify(12L, 6L, token));
    }

    // Note: issue() floors the TTL at 60s, so an expired token cannot be minted
    // via issue() in a unit test; the expiry check (now > expiry) is covered by
    // the integration/smoke path.

    @Test
    void tokenFromDifferentSecretIsRejected() {
        String token = support.issue(12L, 5L);
        AgentApiProperties other = new AgentApiProperties();
        other.setServiceToken("different-secret");
        AgentPreviewTokenSupport otherSupport = new AgentPreviewTokenSupport(other);
        ReflectionTestUtils.setField(otherSupport, "ttlSeconds", 600L);
        assertThrows(IllegalArgumentException.class, () -> otherSupport.verify(12L, 5L, token));
    }
}
