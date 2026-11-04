package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DataDomain;
import com.onedata.portal.service.DataDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据域管理 Controller
 */
@RestController
@RequestMapping("/v1/data-domains")
@RequiredArgsConstructor
public class DataDomainController {

    private final DataDomainService dataDomainService;

    @GetMapping
    public Result<List<DataDomain>> list(@RequestParam(required = false) String businessDomain) {
        return Result.success(dataDomainService.list(businessDomain));
    }

    @PostMapping
    public Result<DataDomain> create(@RequestBody DataDomain domain) {
        return Result.success(dataDomainService.create(domain));
    }

    @PutMapping("/{id}")
    public Result<DataDomain> update(@PathVariable Long id, @RequestBody DataDomain domain) {
        return Result.success(dataDomainService.update(id, domain));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataDomainService.delete(id);
        return Result.success();
    }
}
