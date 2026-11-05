package com.onedata.portal.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonCanonicalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizeSortsObjectKeysAndIsOrderIndependent() throws Exception {
        String a = JsonCanonicalizer.canonicalize(objectMapper.readTree("{\"b\":1,\"a\":2}"));
        String b = JsonCanonicalizer.canonicalize(objectMapper.readTree("{\"a\":2,\"b\":1}"));
        assertEquals(a, b);
        assertEquals("{\"a\":2,\"b\":1}", a);
    }

    @Test
    void canonicalizePreservesArrayOrderAndRecurses() throws Exception {
        String actual = JsonCanonicalizer.canonicalize(objectMapper.readTree("[{\"y\":1,\"x\":2},3]"));
        assertEquals("[{\"x\":2,\"y\":1},3]", actual);
    }

    @Test
    void canonicalizeHandlesNullAndMissingNodes() throws Exception {
        assertEquals("null", JsonCanonicalizer.canonicalize(null));
        assertEquals("null", JsonCanonicalizer.canonicalize(objectMapper.readTree("null")));
    }

    @Test
    void sha256IsDeterministicAndHex() {
        String first = JsonCanonicalizer.sha256("hello");
        String second = JsonCanonicalizer.sha256("hello");
        assertEquals(first, second);
        // 已知向量：sha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", first);
    }

    @Test
    void sha256ReturnsNullForBlankInput() {
        assertNull(JsonCanonicalizer.sha256(null));
        assertNull(JsonCanonicalizer.sha256(""));
        assertNull(JsonCanonicalizer.sha256("   "));
    }

    @Test
    void differentCanonicalFormsProduceDifferentHashes() throws Exception {
        String h1 = JsonCanonicalizer.sha256(JsonCanonicalizer.canonicalize(objectMapper.readTree("{\"a\":1}")));
        String h2 = JsonCanonicalizer.sha256(JsonCanonicalizer.canonicalize(objectMapper.readTree("{\"a\":2}")));
        org.junit.jupiter.api.Assertions.assertNotEquals(h1, h2);
    }
}
