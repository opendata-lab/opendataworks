package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireAuth;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 智能助手设置。
 *
 * <p>两项：DataStudio「智能元数据」生成使用哪个助手，以及门户「智能问数」
 * （菜单页与悬浮窗）使用哪个助手。助手清单由 DataAgent 的
 * {@code /api/v1/dataagent/agents} 提供，这里只持久化选择结果，
 * 避免前端在多个助手之间隐式挑选。
 */
@RestController
@RequestMapping("/v1/settings/agent")
@RequiredArgsConstructor
public class AgentSettingsController {

    /** 智能元数据生成使用的助手 ID */
    static final String METADATA_AGENT_ID_KEY = "metadata.agent_id";

    private static final String METADATA_AGENT_ID_DESC = "智能元数据生成使用的助手 ID";

    /** 门户智能问数使用的助手 ID */
    static final String QUERY_AGENT_ID_KEY = "query.agent_id";

    private static final String QUERY_AGENT_ID_DESC = "门户智能问数使用的助手 ID";

    private final SysConfigService sysConfigService;

    @GetMapping
    public Result<Map<String, String>> getSettings() {
        Map<String, String> payload = new HashMap<>();
        payload.put("metadataAgentId", sysConfigService.get(METADATA_AGENT_ID_KEY));
        payload.put("queryAgentId", sysConfigService.get(QUERY_AGENT_ID_KEY));
        return Result.success(payload);
    }

    @RequireAuth
    @PutMapping
    public Result<Map<String, String>> updateSettings(@RequestBody Map<String, String> body) {
        try {
            // 只更新请求中出现的字段；允许清空：置空表示回退到「未配置」，由前端提示去设置
            if (body != null && body.containsKey("metadataAgentId")) {
                sysConfigService.set(METADATA_AGENT_ID_KEY, body.get("metadataAgentId"), METADATA_AGENT_ID_DESC);
            }
            if (body != null && body.containsKey("queryAgentId")) {
                sysConfigService.set(QUERY_AGENT_ID_KEY, body.get("queryAgentId"), QUERY_AGENT_ID_DESC);
            }
            return getSettings();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }
}
