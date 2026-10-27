package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireRole;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.service.DatasourceSubjectAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/doris-clusters/{sourceId}/accounts")
@RequiredArgsConstructor
public class DatasourceSubjectAccountController {
    private final DatasourceSubjectAccountService service;
    private final SysUserMapper userMapper;

    @RequireRole("admin")
    @GetMapping
    public Result<List<DatasourceSubjectAccount>> list(@PathVariable Long sourceId) {
        return Result.success(service.list(sourceId));
    }

    @RequireRole("admin")
    @PostMapping
    public Result<DatasourceSubjectAccount> save(@PathVariable Long sourceId,
            @RequestBody DatasourceSubjectAccount account) {
        return Result.success(service.save(sourceId, account));
    }

    @RequireRole("admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long sourceId, @PathVariable Long id) {
        service.delete(sourceId, id);
        return Result.success();
    }

    @RequireRole("admin")
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users(@PathVariable Long sourceId) {
        return Result.success(userMapper.selectList(null).stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            return row;
        }).collect(Collectors.toList()));
    }
}
