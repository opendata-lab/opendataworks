package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.BusinessDomain;
import com.onedata.portal.service.BusinessDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 业务域管理 Controller
 */
@RestController
@RequestMapping("/v1/business-domains")
@RequiredArgsConstructor
public class BusinessDomainController {

    private final BusinessDomainService businessDomainService;

    @GetMapping
    public Result<List<BusinessDomain>> list() {
        return Result.success(businessDomainService.listAll());
    }

    @PostMapping
    public Result<BusinessDomain> create(@RequestBody BusinessDomain domain) {
        return Result.success(businessDomainService.create(domain));
    }

    @PutMapping("/{id}")
    public Result<BusinessDomain> update(@PathVariable Long id, @RequestBody BusinessDomain domain) {
        return Result.success(businessDomainService.update(id, domain));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        businessDomainService.delete(id);
        return Result.success();
    }
}
