package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireRole;
import com.onedata.auth.user.SysUser;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.PlatformUserManagementService;
import com.onedata.portal.service.PlatformUserManagementService.UserRequest;
import com.onedata.portal.service.ServiceAccountTokenService;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class PlatformUserManagementController {
    private final PlatformUserManagementService service;
    private final ServiceAccountTokenService tokens;

    @RequireRole("admin")
    @GetMapping
    public Result<List<SysUser>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String accountType) {
        return Result.success(service.list(keyword, enabled, accountType));
    }

    @RequireRole("admin")
    @PostMapping
    public Result<SysUser> create(@RequestBody UserRequest body) {
        return Result.success(service.create(body));
    }

    @RequireRole("admin")
    @PatchMapping("/{id}")
    public Result<SysUser> update(@PathVariable Long id, @RequestBody UserRequest body) {
        return Result.success(service.update(id, body));
    }

    @RequireRole("admin")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody UserRequest body) {
        service.resetPassword(id, body.getPassword());
        return Result.success();
    }

    @RequireRole("admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    @RequireRole("admin")
    @GetMapping("/{id}/tokens")
    public Result<List<com.onedata.portal.entity.ServiceAccountToken>> listTokens(
            @PathVariable Long id) {
        return Result.success(tokens.list(id));
    }

    @RequireRole("admin")
    @PostMapping("/{id}/tokens")
    public Result<Map<String, Object>> createToken(
            @PathVariable Long id,
            @RequestBody ServiceAccountTokenService.TokenRequest body,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return Result.success(tokens.create(id, body));
    }

    @RequireRole("admin")
    @DeleteMapping("/{id}/tokens/{tokenId}")
    public Result<Void> revokeToken(@PathVariable Long id, @PathVariable Long tokenId) {
        tokens.revoke(id, tokenId);
        return Result.success();
    }
}
