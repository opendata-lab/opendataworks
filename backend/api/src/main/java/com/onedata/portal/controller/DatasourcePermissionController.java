package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireRole;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.permission.DatasourcePermissionService;
import com.onedata.portal.service.permission.PermissionModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/v1/doris-clusters/{sourceId}/permissions")
@RequiredArgsConstructor
public class DatasourcePermissionController {
    private final DatasourcePermissionService service;
    @RequireRole("admin") @GetMapping
    public Result<Map<String,Object>> overview(@PathVariable Long sourceId) {return Result.success(service.overview(sourceId));}
    @RequireRole("admin") @PostMapping("/roles")
    public Result<Map<String,Object>> createRole(@PathVariable Long sourceId,@RequestBody RoleRequest input) {return Result.success(service.createRole(sourceId,input));}
    @RequireRole("admin") @PatchMapping("/roles/{id}")
    public Result<Map<String,Object>> renameRole(@PathVariable Long sourceId,@PathVariable Long id,@RequestBody RoleRequest input) {return Result.success(service.renameRole(sourceId,id,input));}
    @RequireRole("admin") @DeleteMapping("/roles/{id}")
    public Result<Map<String,Object>> deleteRole(@PathVariable Long sourceId,@PathVariable Long id) {return Result.success(service.deleteRole(sourceId,id));}
    @RequireRole("admin") @PostMapping("/roles/{id}/preview")
    public Result<Map<String,Object>> preview(@PathVariable Long sourceId,@PathVariable Long id,@RequestBody PreviewRequest input) {return Result.success(service.preview(sourceId,id,input));}
    @RequireRole("admin") @PostMapping("/roles/{id}/apply")
    public Result<Map<String,Object>> apply(@PathVariable Long sourceId,@PathVariable Long id,@RequestBody ApplyRequest input) {return Result.success(service.apply(sourceId,id,input.getPreviewId()));}
    @RequireRole("admin") @PutMapping("/members/{type}/{subject}")
    public Result<Map<String,Object>> putMember(@PathVariable Long sourceId,@PathVariable String type,@PathVariable String subject,@RequestBody MemberRequest input) {return Result.success(service.putMember(sourceId,type,subject,input));}
    @RequireRole("admin") @PostMapping("/members/{type}/{subject}/account")
    public Result<Map<String,Object>> bind(@PathVariable Long sourceId,@PathVariable String type,@PathVariable String subject,@RequestBody AccountRequest input) {return Result.success(service.bindAccount(sourceId,type,subject,input));}
    @RequireRole("admin") @DeleteMapping("/members/{type}/{subject}")
    public Result<Void> remove(@PathVariable Long sourceId,@PathVariable String type,@PathVariable String subject) {service.removeMember(sourceId,type,subject);return Result.success();}
    @RequireRole("admin") @GetMapping("/members/{type}/{subject}/inspect")
    public Result<Map<String,Object>> inspect(@PathVariable Long sourceId,@PathVariable String type,@PathVariable String subject) {return Result.success(service.inspect(sourceId,type,subject));}
    @RequireRole("admin") @PutMapping("/management")
    public Result<Map<String,Object>> management(@PathVariable Long sourceId,@RequestBody AccountRequest input) {return Result.success(service.saveManagement(sourceId,input));}
    @RequireRole("admin") @GetMapping("/resources")
    public Result<Map<String,Object>> resources(@PathVariable Long sourceId,@RequestParam(required=false) String database) {return Result.success(service.resources(sourceId,database));}
    @RequireRole("admin") @GetMapping("/operations")
    public Result<List<Map<String,Object>>> operations(@PathVariable Long sourceId) {return Result.success(service.operations(sourceId));}
}
