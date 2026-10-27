package com.onedata.portal.agentapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentApiAuthInterceptor implements HandlerInterceptor {

    private final AgentApiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AgentIdentityContext.clear();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        if (!StringUtils.hasText(properties.getServiceToken())) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "agent api service token 未配置");
            return false;
        }

        if (properties.isRequirePrivateNetwork() && !isPrivateNetwork(request.getRemoteAddr())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "agent api 仅允许私网访问");
            return false;
        }

        String actualToken = String.valueOf(request.getHeader(properties.getTokenHeaderName()) == null
                ? ""
                : request.getHeader(properties.getTokenHeaderName())).trim();
        if (!properties.getServiceToken().equals(actualToken)) {
            log.warn("Agent API token rejected: uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "agent api token 无效");
            return false;
        }

        try {
            AgentIdentityContext.set(request.getHeader(AgentIdentityContext.USER_HEADER),
                    request.getHeader(AgentIdentityContext.AGENT_HEADER));
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AgentIdentityContext.clear();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("code", status);
        payload.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private boolean isPrivateNetwork(String remoteAddr) {
        if (!StringUtils.hasText(remoteAddr)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
                return true;
            }
            if (address instanceof Inet6Address) {
                String text = address.getHostAddress();
                return text.startsWith("fc") || text.startsWith("fd");
            }
            return false;
        } catch (Exception ex) {
            log.warn("Failed to parse remote address `{}` for agent api auth", remoteAddr, ex);
            return false;
        }
    }
}
