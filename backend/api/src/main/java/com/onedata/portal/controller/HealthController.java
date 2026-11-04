package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 容器就绪探针，避免依赖额外 actuator 配置。
 */
@RestController
@RequestMapping("/v1/health")
public class HealthController {

    @GetMapping
    public Result<java.util.Map<String, String>> health() {
        return Result.success(Collections.singletonMap("status", "UP"));
    }
}
