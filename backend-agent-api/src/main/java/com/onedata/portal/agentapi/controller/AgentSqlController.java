package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.dto.AgentSqlAnalyzeRequest;
import com.onedata.portal.agentapi.service.AgentSqlService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/ai/sql")
public class AgentSqlController {

    private final AgentSqlService agentSqlService;

    @PostMapping("/analyze")
    public Object analyze(@Validated @RequestBody AgentSqlAnalyzeRequest request) {
        return agentSqlService.analyze(request);
    }
}
