package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.dto.AgentTaskUpsertRequest;
import com.onedata.portal.agentapi.service.AgentTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/ai/task")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    @PostMapping
    public Object create(
            @Validated @RequestBody AgentTaskUpsertRequest request,
            @RequestHeader(value = "X-Agent-Operator", required = false) String operator) {
        return agentTaskService.createTask(request, operator);
    }

    @PutMapping("/{id}")
    public Object update(
            @PathVariable("id") Long id,
            @Validated @RequestBody AgentTaskUpsertRequest request,
            @RequestHeader(value = "X-Agent-Operator", required = false) String operator) {
        return agentTaskService.updateTask(id, request, operator);
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable("id") Long id) {
        return agentTaskService.getTask(id);
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return agentTaskService.listTasks(keyword, status, limit);
    }
}
