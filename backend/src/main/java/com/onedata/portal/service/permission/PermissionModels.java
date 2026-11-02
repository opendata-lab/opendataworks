package com.onedata.portal.service.permission;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import java.util.ArrayList;
import java.util.List;

public final class PermissionModels {
    private PermissionModels() {}

    @Data public static class RoleRequest { private String name; private String template; }
    @Data public static class GrantSelection {
        private String resourceType;
        private String database;
        private String tableName;
        private List<String> actions = new ArrayList<>();
    }
    @Data public static class PreviewRequest {
        private Integer expectedRevision;
        private List<GrantSelection> grants = new ArrayList<>();
    }
    @Data public static class ApplyRequest { private Long previewId; }
    @Data public static class MemberRequest {
        private List<Long> roleIds = new ArrayList<>();
        private Integer expectedRevision;
    }
    @Data public static class AccountRequest {
        private String username;
        @ToString.Exclude private String password;
        private Integer expectedRevision;
    }
    @Data @NoArgsConstructor @AllArgsConstructor public static class Atom {
        private String kind;
        private String database;
        private String object;
        private String privilege;
    }
    @Data @NoArgsConstructor @AllArgsConstructor public static class Identity {
        private String user;
        private String host;
    }
}
