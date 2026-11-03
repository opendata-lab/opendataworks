package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentInspectResponse;
import com.onedata.portal.agentapi.dto.AgentLineageResponse;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteRequest;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteResponse;
import com.onedata.portal.agentapi.dto.AgentTableDdlResponse;
import com.onedata.portal.agentapi.service.AgentMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/ai/metadata")
public class AgentMetadataController {

    private final AgentMetadataService agentMetadataService;

    @GetMapping("/inspect")
    public AgentInspectResponse inspect(
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "12") int tableLimit) {
        return agentMetadataService.inspect(database, table, keyword, tableLimit);
    }

    @GetMapping("/lineage")
    public AgentLineageResponse lineage(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String dbName,
            @RequestParam(required = false) Long tableId,
            @RequestParam(required = false) Integer depth) {
        return agentMetadataService.lineage(table, dbName, tableId, depth);
    }

    @GetMapping("/datasource/resolve")
    public AgentDatasourceResolution resolveDatasource(
            @RequestParam String database,
            @RequestParam(required = false) String preferredEngine) {
        return agentMetadataService.resolveDatasource(database, preferredEngine);
    }

    @GetMapping("/ddl")
    public AgentTableDdlResponse ddl(
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) Long tableId) {
        return agentMetadataService.ddl(database, table, tableId);
    }

    @GetMapping("/export")
    public List<Map<String, Object>> export(
            @RequestParam String kind,
            @RequestParam(required = false) String database) {
        String normalizedKind = String.valueOf(kind).trim().toLowerCase(Locale.ROOT);
        switch (normalizedKind) {
            case "tables":
                return agentMetadataService.exportTables(database);
            case "lineage":
                return agentMetadataService.exportLineage(database);
            case "datasource":
                return agentMetadataService.exportDatasource(database);
            default:
                throw new IllegalArgumentException("unsupported export kind: " + kind);
        }
    }

    @PostMapping("/complete")
    public AgentMetadataCompleteResponse complete(
            @RequestBody AgentMetadataCompleteRequest request) {
        return agentMetadataService.complete(request, AgentIdentityContext.require().getAgentId());
    }
}
