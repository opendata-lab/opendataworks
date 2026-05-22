package com.onedata.portal.agentapi.scope;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AgentDataScopeFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Agent-Data-Scope";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || (!path.startsWith("/v1/ai/") && !path.startsWith("/api/v1/ai/"))) {
            filterChain.doFilter(request, response);
            return;
        }
        AgentDataScopeContext.setEncodedScope(request.getHeader(HEADER_NAME));
        try {
            filterChain.doFilter(request, response);
        } finally {
            AgentDataScopeContext.clear();
        }
    }
}
