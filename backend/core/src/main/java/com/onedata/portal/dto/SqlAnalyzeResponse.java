package com.onedata.portal.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;



/**
 * SQL 风险分析响应
 */
@Data
public class SqlAnalyzeResponse {

    /**
     * 拆分后的语句元信息
     */
    private List<StatementInfo> statements;

    /**
     * 语句级风控结果
     */
    private List<RiskItem> riskItems;

    /**
     * 需要强确认的挑战信息
     */
    private List<ConfirmChallenge> confirmChallenges;

    /**
     * 是否存在阻断语句
     */
    private boolean blocked;

    /**
     * 首个阻断原因
     */
    private String blockedReason;

    @Data
    public static class StatementInfo {
        private Integer statementIndex;
        private String sqlSnippet;
        private String sqlType;
    }

    @Data
    public static class RiskItem {
        private Integer statementIndex;
        private String sqlType;
        private String riskLevel;
        private String parseStatus;
        private boolean requiresConfirm;
        private String targetObject;
        private boolean blocked;
        private String blockedReason;
    }

    @Data
    public static class ConfirmChallenge {
        private Integer statementIndex;
        private String targetObject;
        private String confirmTextHint;
        private String confirmToken;
        private LocalDateTime expireAt;
    }
}
