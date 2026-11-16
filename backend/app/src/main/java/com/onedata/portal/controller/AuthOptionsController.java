package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 登录方式选项接口（从 Spring Boot OAuth2ClientProperties 读取已配置的登录提供方） */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthOptionsController {

    private final ObjectProvider<OAuth2ClientProperties> clientPropertiesProvider;

    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        List<Map<String, String>> oauth = new ArrayList<>();
        OAuth2ClientProperties properties = clientPropertiesProvider.getIfAvailable();
        if (properties != null && properties.getRegistration() != null) {
            for (Map.Entry<String, OAuth2ClientProperties.Registration> entry :
                    properties.getRegistration().entrySet()) {
                String id = entry.getKey();
                OAuth2ClientProperties.Registration registration = entry.getValue();
                String name =
                        registration != null && StringUtils.hasText(registration.getClientName())
                                ? registration.getClientName()
                                : id;
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("name", name);
                oauth.add(item);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("localLogin", true);
        data.put("oauth", oauth);
        return Result.success(data);
    }
}
