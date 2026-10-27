package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.model.DatasourceTypeDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/datasource-types")
@RequiredArgsConstructor
public class DatasourceTypeController {

    private final DatasourceAdapterRegistry registry;

    @GetMapping
    public Result<List<DatasourceTypeDefinition>> list() {
        return Result.success(registry.definitions());
    }
}
