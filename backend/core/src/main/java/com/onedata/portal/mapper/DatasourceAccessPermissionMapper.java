package com.onedata.portal.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * Fixed, parameterized platform permission SQL. Native datasource SQL stays on its own connection.
 */
public interface DatasourceAccessPermissionMapper {
    @Select(
            "SELECT COUNT(*) FROM datasource_access_member m WHERE m.source_id=#{source} AND"
                + " m.subject_type=#{type} AND m.subject_id=#{subject} AND (m.status IN"
                + " ('ERROR','APPLYING') OR EXISTS (SELECT 1 FROM datasource_access_member_role mr"
                + " JOIN datasource_access_role r ON r.id=mr.role_id WHERE mr.member_id=m.id AND"
                + " r.status IN ('ERROR','APPLYING')))")
    Long countUncertain(
            @Param("source") Long source,
            @Param("type") String type,
            @Param("subject") String subject);

    @Insert(
            "INSERT INTO datasource_access_member_role(member_id,role_id)"
                    + " VALUES(#{memberId},#{roleId})")
    int addMemberRole(@Param("memberId") Object memberId, @Param("roleId") Object roleId);

    @Delete("DELETE FROM datasource_access_member_role WHERE member_id=#{memberId}")
    int clearMemberRoles(@Param("memberId") Object memberId);

    @Select("SELECT COUNT(*) FROM datasource_access_member_role WHERE role_id=#{roleId}")
    Long countRoleMembers(@Param("roleId") Object roleId);

    @Select(
            "SELECT role_id FROM datasource_access_member_role WHERE member_id=#{memberId} ORDER BY"
                    + " role_id")
    List<Map<String, Object>> listMemberRoleIds(@Param("memberId") Object memberId);

    @Select(
            "SELECT * FROM datasource_access_member WHERE source_id=#{sourceId} AND"
                    + " subject_type=#{subjectType} AND subject_id=#{subjectId}")
    List<Map<String, Object>> findMember(
            @Param("sourceId") Object sourceId,
            @Param("subjectType") Object subjectType,
            @Param("subjectId") Object subjectId);

    @Select("SELECT * FROM datasource_access_role WHERE id=#{roleId} AND source_id=#{sourceId}")
    List<Map<String, Object>> findRole(
            @Param("roleId") Object roleId, @Param("sourceId") Object sourceId);

    @Select("SELECT * FROM datasource_permission_manager WHERE source_id=#{sourceId}")
    List<Map<String, Object>> findManagementAccount(@Param("sourceId") Object sourceId);

    @Select("SELECT * FROM datasource_access_role WHERE source_id=#{sourceId} ORDER BY id")
    List<Map<String, Object>> listRoles(@Param("sourceId") Object sourceId);

    @Select("SELECT * FROM datasource_access_grant WHERE role_id=#{roleId}")
    List<Map<String, Object>> listRoleGrants(@Param("roleId") Object roleId);

    @Select(
            "SELECT * FROM datasource_permission_operation WHERE source_id=#{sourceId} ORDER BY id"
                    + " DESC LIMIT 100")
    List<Map<String, Object>> listOperations(@Param("sourceId") Object sourceId);

    @Select("SELECT * FROM datasource_permission_operation WHERE id=#{operationId}")
    List<Map<String, Object>> findOperation(@Param("operationId") Object operationId);

    @Update("UPDATE datasource_access_role SET status='ERROR' WHERE id=#{roleId}")
    int markRoleError(@Param("roleId") Object roleId);

    @Update(
            "UPDATE datasource_permission_operation SET"
                    + " state=#{state},message=#{message},steps=#{steps} WHERE id=#{operationId}")
    int finishOperation(
            @Param("state") Object state,
            @Param("message") Object message,
            @Param("steps") Object steps,
            @Param("operationId") Object operationId);

    @Update(
            "UPDATE datasource_permission_operation SET"
                    + " state='SUCCESS',message='角色已删除',steps=#{steps} WHERE id=#{operationId}")
    int finishRoleDeletion(@Param("steps") Object steps, @Param("operationId") Object operationId);

    @Delete("DELETE FROM datasource_access_role WHERE id=#{roleId}")
    int deleteRole(@Param("roleId") Object roleId);

    @Delete("DELETE FROM datasource_access_grant WHERE role_id=#{roleId}")
    int deleteRoleGrants(@Param("roleId") Object roleId);

    @Update("UPDATE datasource_permission_operation SET steps=#{steps} WHERE id=#{operationId}")
    int recordOperationSteps(
            @Param("steps") Object steps, @Param("operationId") Object operationId);

    @Insert(
            "INSERT INTO datasource_permission_operation(source_id,role_id,operator_id,state)"
                    + " VALUES(#{sourceId},#{roleId},#{operatorId},#{state})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createRoleOperationRow(Map<String, Object> row);

    default Long createRoleOperation(
            @Param("sourceId") Object sourceId,
            @Param("roleId") Object roleId,
            @Param("operatorId") Object operatorId,
            @Param("state") Object state) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("roleId", roleId);
        row.put("operatorId", operatorId);
        row.put("state", state);
        createRoleOperationRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Update("UPDATE datasource_access_member SET status=#{status} WHERE id=#{memberId}")
    int setMemberStatus(@Param("status") Object status, @Param("memberId") Object memberId);

    @Update(
            "UPDATE datasource_permission_operation SET"
                    + " state='SUCCESS',message=#{message},steps=#{steps} WHERE id=#{operationId}")
    int completeOperation(
            @Param("message") Object message,
            @Param("steps") Object steps,
            @Param("operationId") Object operationId);

    @Update(
            "UPDATE datasource_access_member SET"
                    + " revision=revision+1,status='ACTIVE',native_identity=#{nativeIdentity} WHERE"
                    + " id=#{memberId}")
    int activateMember(
            @Param("nativeIdentity") Object nativeIdentity, @Param("memberId") Object memberId);

    @Insert(
            "INSERT INTO datasource_access_member(source_id,subject_type,subject_id)"
                    + " VALUES(#{sourceId},#{subjectType},#{subjectId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createMemberRow(Map<String, Object> row);

    default Long createMember(
            @Param("sourceId") Object sourceId,
            @Param("subjectType") Object subjectType,
            @Param("subjectId") Object subjectId) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("subjectType", subjectType);
        row.put("subjectId", subjectId);
        createMemberRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Delete("DELETE FROM datasource_access_member WHERE id=#{memberId}")
    int deleteMember(@Param("memberId") Object memberId);

    @Select("SELECT * FROM datasource_access_role WHERE source_id=#{sourceId}")
    List<Map<String, Object>> listRoleSnapshots(@Param("sourceId") Object sourceId);

    @Update("UPDATE datasource_access_member SET status='APPLYING' WHERE id=#{memberId}")
    int markMemberApplying(@Param("memberId") Object memberId);

    @Insert(
            "INSERT INTO"
                + " datasource_permission_operation(source_id,subject_type,subject_id,operator_id,state,message)"
                + " VALUES(#{sourceId},#{subjectType},#{subjectId},#{operatorId},#{state},#{message})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createMemberOperationRow(Map<String, Object> row);

    default Long createMemberOperation(
            @Param("sourceId") Object sourceId,
            @Param("subjectType") Object subjectType,
            @Param("subjectId") Object subjectId,
            @Param("operatorId") Object operatorId,
            @Param("state") Object state,
            @Param("message") Object message) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("subjectType", subjectType);
        row.put("subjectId", subjectId);
        row.put("operatorId", operatorId);
        row.put("state", state);
        row.put("message", message);
        createMemberOperationRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Insert(
            "INSERT INTO"
                + " datasource_permission_operation(source_id,subject_type,subject_id,operator_id,state,message,steps)"
                + " VALUES(#{sourceId},#{subjectType},#{subjectId},#{operatorId},'SUCCESS',#{message},'[]')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int recordMemberChangeRow(Map<String, Object> row);

    default Long recordMemberChange(
            @Param("sourceId") Object sourceId,
            @Param("subjectType") Object subjectType,
            @Param("subjectId") Object subjectId,
            @Param("operatorId") Object operatorId,
            @Param("message") Object message) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("subjectType", subjectType);
        row.put("subjectId", subjectId);
        row.put("operatorId", operatorId);
        row.put("message", message);
        recordMemberChangeRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Update(
            "UPDATE datasource_access_member SET revision=revision+1,status='NEEDS_ACCOUNT' WHERE"
                    + " id=#{memberId}")
    int markMemberNeedsAccount(@Param("memberId") Object memberId);

    @Insert(
            "INSERT INTO datasource_permission_operation(source_id,operator_id,state,message,steps)"
                    + " VALUES(#{sourceId},#{operatorId},'SUCCESS',#{message},'[]')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int recordManagementChangeRow(Map<String, Object> row);

    default Long recordManagementChange(
            @Param("sourceId") Object sourceId,
            @Param("operatorId") Object operatorId,
            @Param("message") Object message) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("operatorId", operatorId);
        row.put("message", message);
        recordManagementChangeRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Insert(
            "INSERT INTO datasource_permission_manager(source_id,username,password)"
                    + " VALUES(#{sourceId},#{username},#{password}) ON DUPLICATE KEY UPDATE"
                    + " username=VALUES(username),password=VALUES(password)")
    int upsertManagementAccount(
            @Param("sourceId") Object sourceId,
            @Param("username") Object username,
            @Param("password") Object password);

    @Update("UPDATE datasource_access_role SET status=#{status} WHERE id=#{roleId}")
    int setRoleStatus(@Param("status") Object status, @Param("roleId") Object roleId);

    @Update(
            "UPDATE datasource_access_role SET"
                    + " status='ACTIVE',revision=revision+1,native_grants=#{nativeGrants} WHERE"
                    + " id=#{roleId}")
    int activateRole(@Param("nativeGrants") Object nativeGrants, @Param("roleId") Object roleId);

    @Insert(
            "INSERT INTO"
                + " datasource_access_grant(role_id,resource_type,database_name,table_name,actions)"
                + " VALUES(#{roleId},#{resourceType},#{databaseName},#{tableName},#{actions})")
    int insertRoleGrant(
            @Param("roleId") Object roleId,
            @Param("resourceType") Object resourceType,
            @Param("databaseName") Object databaseName,
            @Param("tableName") Object tableName,
            @Param("actions") Object actions);

    @Update("UPDATE datasource_access_role SET status='APPLYING' WHERE id=#{roleId}")
    int markRoleApplying(@Param("roleId") Object roleId);

    @Update("UPDATE datasource_permission_operation SET state='APPLYING' WHERE id=#{operationId}")
    int markOperationApplying(@Param("operationId") Object operationId);

    @Select(
            "SELECT * FROM datasource_permission_operation WHERE id=#{operationId} AND"
                    + " source_id=#{sourceId} AND role_id=#{roleId}")
    List<Map<String, Object>> findRoleOperation(
            @Param("operationId") Object operationId,
            @Param("sourceId") Object sourceId,
            @Param("roleId") Object roleId);

    @Insert(
            "INSERT INTO"
                + " datasource_permission_operation(source_id,role_id,operator_id,revision,state,payload,steps,expires_at)"
                + " VALUES(#{sourceId},#{roleId},#{operatorId},#{revision},#{state},#{payload},#{steps},#{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createGrantPreviewRow(Map<String, Object> row);

    default Long createGrantPreview(
            @Param("sourceId") Object sourceId,
            @Param("roleId") Object roleId,
            @Param("operatorId") Object operatorId,
            @Param("revision") Object revision,
            @Param("state") Object state,
            @Param("payload") Object payload,
            @Param("steps") Object steps,
            @Param("expiresAt") Object expiresAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("roleId", roleId);
        row.put("operatorId", operatorId);
        row.put("revision", revision);
        row.put("state", state);
        row.put("payload", payload);
        row.put("steps", steps);
        row.put("expiresAt", expiresAt);
        createGrantPreviewRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Select(
            "SELECT payload FROM datasource_permission_operation WHERE source_id=#{sourceId} AND"
                    + " role_id=#{roleId} AND state IN ('PARTIAL','UNKNOWN','APPLYING')")
    List<Map<String, Object>> listUncertainRolePayloads(
            @Param("sourceId") Object sourceId, @Param("roleId") Object roleId);

    @Insert(
            "INSERT INTO"
                + " datasource_permission_operation(source_id,role_id,operator_id,state,message,steps)"
                + " VALUES(#{sourceId},#{roleId},#{operatorId},'SUCCESS',#{message},'[]')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int recordRoleChangeRow(Map<String, Object> row);

    default Long recordRoleChange(
            @Param("sourceId") Object sourceId,
            @Param("roleId") Object roleId,
            @Param("operatorId") Object operatorId,
            @Param("message") Object message) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("roleId", roleId);
        row.put("operatorId", operatorId);
        row.put("message", message);
        recordRoleChangeRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Update(
            "UPDATE datasource_access_role SET name=#{name},template=#{template},revision=revision+1 WHERE id=#{roleId} AND"
                    + " source_id=#{sourceId}")
    int updateRole(
            @Param("name") Object name,
            @Param("template") Object template,
            @Param("roleId") Object roleId,
            @Param("sourceId") Object sourceId);

    @Insert(
            "INSERT INTO datasource_access_role(source_id,name,native_name,template)"
                    + " VALUES(#{sourceId},#{name},#{nativeName},#{template})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createRoleRow(Map<String, Object> row);

    default Long createRole(
            @Param("sourceId") Object sourceId,
            @Param("name") Object name,
            @Param("nativeName") Object nativeName,
            @Param("template") Object template) {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceId", sourceId);
        row.put("name", name);
        row.put("nativeName", nativeName);
        row.put("template", template);
        createRoleRow(row);
        return ((Number) row.get("id")).longValue();
    }

    @Select("SELECT * FROM datasource_access_member WHERE source_id=#{sourceId} ORDER BY id")
    List<Map<String, Object>> listMembers(@Param("sourceId") Object sourceId);

    /**
     * One row per member role (role columns null for a member without roles) on live sources, plus
     * account bindings without a member row, which the source overview also lists as members.
     */
    @Select(
            "SELECT m.subject_type, m.subject_id, m.source_id, c.cluster_name, m.status,"
                    + " r.name AS role_name FROM datasource_access_member m JOIN doris_cluster c"
                    + " ON c.id=m.source_id AND c.deleted=0 LEFT JOIN datasource_access_member_role"
                    + " mr ON mr.member_id=m.id LEFT JOIN datasource_access_role r ON"
                    + " r.id=mr.role_id UNION ALL SELECT a.subject_type, a.subject_id, a.source_id,"
                    + " c.cluster_name, 'ACTIVE' AS status, NULL AS role_name FROM"
                    + " datasource_subject_account a JOIN doris_cluster c ON c.id=a.source_id AND"
                    + " c.deleted=0 WHERE NOT EXISTS (SELECT 1 FROM datasource_access_member m"
                    + " WHERE m.source_id=a.source_id AND m.subject_type=a.subject_type AND"
                    + " m.subject_id=a.subject_id) ORDER BY source_id, role_name")
    List<Map<String, Object>> listAccessSummaryRows();
}
