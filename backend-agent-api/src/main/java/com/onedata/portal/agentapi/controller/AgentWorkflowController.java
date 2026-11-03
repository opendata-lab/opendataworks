package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.agentapi.dto.AgentPublishPreviewResponse;
import com.onedata.portal.agentapi.dto.AgentPublishRequest;
import com.onedata.portal.agentapi.dto.AgentScheduleOnlineRequest;
import com.onedata.portal.agentapi.dto.AgentScheduleUpsertRequest;
import com.onedata.portal.agentapi.dto.AgentWorkflowUpsertRequest;
import com.onedata.portal.agentapi.service.AgentWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/ai/workflow")
public class AgentWorkflowController {

    private final AgentWorkflowService agentWorkflowService;

    @PostMapping
    public Object create(
            @Validated @RequestBody AgentWorkflowUpsertRequest request) {
        return agentWorkflowService.createWorkflow(request, AgentIdentityContext.require().getAgentId());
    }

    @PutMapping("/{id}")
    public Object update(
            @PathVariable("id") Long id,
            @Validated @RequestBody AgentWorkflowUpsertRequest request) {
        return agentWorkflowService.updateWorkflow(id, request, AgentIdentityContext.require().getAgentId());
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable("id") Long id) {
        return agentWorkflowService.getWorkflow(id);
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return agentWorkflowService.listWorkflows(keyword, status, limit);
    }

    @GetMapping("/{id}/publish/preview")
    public AgentPublishPreviewResponse previewPublish(@PathVariable("id") Long id) {
        return agentWorkflowService.previewPublish(id);
    }

    @PostMapping("/{id}/publish")
    public Object publish(
            @PathVariable("id") Long id,
            @Validated @RequestBody AgentPublishRequest request) {
        return agentWorkflowService.publish(id, request, AgentIdentityContext.require().getAgentId());
    }

    @PutMapping("/{id}/schedule")
    public Object upsertSchedule(
            @PathVariable("id") Long id,
            @Validated @RequestBody AgentScheduleUpsertRequest request) {
        return agentWorkflowService.upsertSchedule(id, request);
    }

    @PostMapping("/{id}/schedule/online")
    public Object scheduleOnline(
            @PathVariable("id") Long id,
            @Validated @RequestBody AgentScheduleOnlineRequest request) {
        return agentWorkflowService.scheduleOnline(id, request.getPreviewToken());
    }

    @PostMapping("/{id}/schedule/offline")
    public Object scheduleOffline(
            @PathVariable("id") Long id) {
        return agentWorkflowService.scheduleOffline(id);
    }
}
