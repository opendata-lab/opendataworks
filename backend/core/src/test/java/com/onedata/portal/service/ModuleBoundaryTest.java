package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class ModuleBoundaryTest {
    @Test
    void coreHasNoHttpMcpOrRestEnvelopeImports() throws Exception {
        Path source = Path.of("src/main/java");
        assertTrue(Files.isDirectory(source));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String forbidden :
                        List.of(
                                "import jakarta.servlet.",
                                "import io.modelcontextprotocol.",
                                "import com.onedata.portal.dto.Result;",
                                "import org.springframework.web.bind.annotation.",
                                "import org.springframework.web.servlet."))
                    assertFalse(text.contains(forbidden), file + " imports " + forbidden);
            }
        }
    }

    @Test
    void protocolAdaptersHaveNoMutualDependencies() throws Exception {
        String api = Files.readString(Path.of("../api/pom.xml"));
        String mcp = Files.readString(Path.of("../mcp-server/pom.xml"));
        assertFalse(api.contains("<artifactId>opendataworks-mcp-server</artifactId>"));
        assertFalse(mcp.contains("<artifactId>opendataworks-api</artifactId>"));
    }
}
