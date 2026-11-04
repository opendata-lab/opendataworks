package com.onedata.portal.service.permission;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.Namespace;
import com.onedata.portal.service.datasource.model.TableMetadata;
import com.onedata.portal.service.permission.PermissionModels.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import javax.sql.DataSource;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class DatasourcePermissionService {
    private final com.onedata.portal.mapper.DatasourceAccessPermissionMapper permissions;
    private final DataSource platformDataSource;
    private final PlatformTransactionManager transactionManager;
    private final DorisClusterMapper clusters;
    private final SysUserMapper users;
    private final com.onedata.portal.mapper.DatasourceSubjectAccountMapper accountMapper;
    private final DatasourceConnectionFactory connections;
    private final DatasourceAdapterRegistry adapters;
    private final PermissionCredentialCipher cipher;
    private final ObjectMapper json;

    public Map<String, Object> overview(Long source) {
        DorisCluster cluster = source(source);
        List<Map<String, Object>> roles = roleViews(source);
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> row : query(permissions.listMembers(source)))
            members.add(memberView(source, row));
        // Account mappings remain the single connection source; include mappings registered outside
        // this UI.
        for (DatasourceSubjectAccount account : listMappings(source)) {
            if (members.stream()
                    .noneMatch(
                            m ->
                                    account.getSubjectType().equals(m.get("subjectType"))
                                            && account.getSubjectId()
                                                    .equals(String.valueOf(m.get("subjectId"))))) {
                Map<String, Object> row =
                        map(
                                "subjectType",
                                account.getSubjectType(),
                                "subjectId",
                                account.getSubjectId(),
                                "revision",
                                0,
                                "status",
                                "ACTIVE");
                members.add(memberView(source, row));
            }
        }
        Map<String, Object> manager = managerRow(source);
        return map(
                "sourceId",
                source,
                "engine",
                cluster.getSourceType(),
                "roles",
                roles,
                "members",
                members,
                "management",
                map(
                        "username",
                        manager == null ? cluster.getUsername() : manager.get("username"),
                        "configured",
                        manager != null),
                "capabilities",
                nativeAdapter(cluster).capabilities());
    }

    public Map<String, Object> createRole(Long source, RoleRequest request) {
        source(source);
        validateRoleName(request.getName());
        String template = request.getTemplate() == null ? "CUSTOM" : request.getTemplate();
        if (!Arrays.asList("READONLY", "READWRITE", "ADMIN", "CUSTOM").contains(template))
            throw new IllegalArgumentException("无效角色模板");
        return locked(
                source,
                () -> {
                    Long id =
                            permissions.createRole(
                                    source,
                                    request.getName().trim(),
                                    "odw_"
                                            + Long.toString(source, 36)
                                            + "_"
                                            + UUID.randomUUID()
                                                    .toString()
                                                    .replace("-", "")
                                                    .substring(0, 12),
                                    template);
                    permissions.recordRoleChange(
                            source, id, operator(), "新建角色草稿：" + request.getName().trim());
                    return roleView(role(source, id));
                });
    }

    public Map<String, Object> renameRole(Long source, Long id, RoleRequest input) {
        validateRoleName(input.getName());
        return locked(
                source,
                () -> {
                    role(source, id);
                    permissions.renameRole(input.getName().trim(), id, source);
                    permissions.recordRoleChange(
                            source, id, operator(), "修改角色名称：" + input.getName().trim());
                    return roleView(role(source, id));
                });
    }

    public Map<String, Object> preview(Long source, Long id, PreviewRequest input) {
        return locked(
                source,
                () -> {
                    Map<String, Object> role = role(source, id);
                    revision(role, input.getExpectedRevision());
                    try (Connection c = management(source)) {
                        NativePermissionAdapter adapter = nativeAdapter(source(source));
                        adapter.configure(c);
                        boolean exists = adapter.roleExists(c, text(role, "nativeName"));
                        if ("DRAFT".equals(role.get("status")) && exists)
                            throw new IllegalArgumentException("原生角色已存在，不能接管外部角色");
                        Set<Atom> before =
                                exists
                                        ? adapter.readRole(c, text(role, "nativeName"))
                                        : new LinkedHashSet<>();
                        if (!"DRAFT".equals(role.get("status")) && !exists)
                            throw new IllegalArgumentException("原生角色已被外部删除，请核对授权记录");
                        Set<Atom> owned = atoms(role.get("nativeGrants"));
                        // Failed requests also establish explicit management intent; unknown
                        // external grants are never silently revoked.
                        for (Map<String, Object> op :
                                query(permissions.listUncertainRolePayloads(source, id))) {
                            Map<String, Object> payload = object(op.get("payload"));
                            if (payload.get("target") != null)
                                owned.addAll(atoms(payload.get("target")));
                        }
                        if (!owned.containsAll(before))
                            throw new IllegalArgumentException("原生角色存在平台未托管的权限，请先核对并处理外部授权");
                        Set<Atom> target = expand(c, source, adapter, input.getGrants());
                        boolean readonly = readOnly(input.getGrants());
                        if ("READONLY".equals(role.get("template")) && !readonly)
                            throw new IllegalArgumentException("只读模板不能授予写入／结构权限");
                        List<String> sql =
                                diff(adapter, text(role, "nativeName"), before, target, !exists);
                        List<String> warnings = new ArrayList<>();
                        warnings.add("原生账号已有直接授权、外部角色或所有权可能扩大有效范围；本次只修改此托管角色。");
                        if (input.getGrants().stream()
                                .anyMatch(g -> "SCHEMA".equals(g.getResourceType())))
                            warnings.add(
                                    adapter.postgres()
                                            ? "Schema查询／写入仅展开当前表和视图，不覆盖未来对象。"
                                            : "库级授权覆盖当前及未来对象；DROP可能允许删库。");
                        if (!before.equals(atoms(role.get("nativeGrants"))))
                            warnings.add("原生状态与上次平台记录不同，请逐项核对本次变更后再确认。");
                        Map<String, Object> payload =
                                map(
                                        "before",
                                        before,
                                        "target",
                                        target,
                                        "grants",
                                        input.getGrants(),
                                        "sql",
                                        sql,
                                        "createRole",
                                        !exists,
                                        "literalDatabaseNames",
                                        adapter.literalDatabaseNames());
                        Long operation =
                                permissions.createGrantPreview(
                                        source,
                                        id,
                                        operator(),
                                        role.get("revision"),
                                        "PREVIEW",
                                        write(payload),
                                        "[]",
                                        Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)));
                        return map(
                                "previewId",
                                operation,
                                "sql",
                                sql,
                                "warnings",
                                warnings,
                                "memberCount",
                                memberCount(id),
                                "expiresAt",
                                LocalDateTime.now().plusMinutes(10).toString());
                    }
                });
    }

    public Map<String, Object> apply(Long source, Long id, Long preview) {
        return locked(
                source,
                () -> {
                    Map<String, Object> role = role(source, id);
                    Map<String, Object> op =
                            one(permissions.findRoleOperation(preview, source, id));
                    if (op == null || !operator().equals(text(op, "operatorId")))
                        throw new IllegalArgumentException("预览不存在或不属于当前操作者");
                    if (!"PREVIEW".equals(op.get("state"))) return operationView(op);
                    revision(role, number(op.get("revision")));
                    Object deadline = op.get("expiresAt");
                    LocalDateTime expires =
                            deadline instanceof Timestamp
                                    ? ((Timestamp) deadline).toLocalDateTime()
                                    : (LocalDateTime) deadline;
                    if (expires.isBefore(LocalDateTime.now()))
                        throw new PermissionConflictException("预览已过期，请重新预览");
                    Map<String, Object> payload = object(op.get("payload"));
                    Set<Atom> expected = atoms(payload.get("before")),
                            target = atoms(payload.get("target"));
                    List<String> sql =
                            json.convertValue(
                                    payload.get("sql"), new TypeReference<List<String>>() {});
                    List<Map<String, Object>> steps = new ArrayList<>();
                    NativePermissionAdapter adapter = nativeAdapter(source(source));
                    boolean attempted = false;
                    try (Connection c = management(source)) {
                        adapter.configure(c);
                        if (adapter.literalDatabaseNames()
                                != Boolean.TRUE.equals(payload.get("literalDatabaseNames")))
                            throw new PermissionConflictException("数据库授权模式已变化，请重新预览");
                        boolean exists = adapter.roleExists(c, text(role, "nativeName"));
                        if (exists == Boolean.TRUE.equals(payload.get("createRole"))
                                || !(exists
                                                ? adapter.readRole(c, text(role, "nativeName"))
                                                : Collections.emptySet())
                                        .equals(expected))
                            throw new PermissionConflictException("原生权限自预览后发生变化，请重新预览");
                        // Re-check Agent membership at apply time, not merely when the preview was
                        // created.
                        List<GrantSelection> selections =
                                json.convertValue(
                                        payload.get("grants"),
                                        new TypeReference<List<GrantSelection>>() {});
                        permissions.markOperationApplying(preview);
                        permissions.markRoleApplying(id);
                        for (String statement : sql) {
                            attempted = true;
                            Map<String, Object> step = map("sql", statement, "state", "UNKNOWN");
                            steps.add(step);
                            try {
                                execute(c, statement);
                                step.put("state", "SUCCESS");
                            } catch (SQLException e) {
                                step.put("state", uncertain(e) ? "UNKNOWN" : "FAILED");
                                throw e;
                            }
                            permissions.recordOperationSteps(write(steps), preview);
                        }
                        if (!adapter.readRole(c, text(role, "nativeName")).equals(target))
                            throw new SQLException("原生权限回读与批准结果不一致");
                        platformTransaction()
                                .execute(
                                        status -> {
                                            permissions.deleteRoleGrants(id);
                                            for (GrantSelection grant : selections)
                                                permissions.insertRoleGrant(
                                                        id,
                                                        grant.getResourceType(),
                                                        grant.getDatabase(),
                                                        grant.getTableName(),
                                                        write(grant.getActions()));
                                            permissions.activateRole(write(target), id);
                                            permissions.completeOperation(
                                                    "已下发并核验", write(steps), preview);
                                            return null;
                                        });
                    } catch (PermissionConflictException e) {
                        throw e;
                    } catch (Exception e) {
                        String state = failureState(e, steps, attempted);
                        permissions.finishOperation(state, safeMessage(e), write(steps), preview);
                        permissions.setRoleStatus(
                                "FAILED".equals(state) ? role.get("status") : "ERROR", id);
                    }
                    return operationView(one(permissions.findOperation(preview)));
                });
    }

    public Map<String, Object> resources(Long source, String database) {
        DorisCluster cluster = source(source);
        try (Connection c = management(source)) {
            List<String> namespaces =
                    adapters.require(cluster.getSourceType()).metadata().namespaces(c).stream()
                            .map(Namespace::getName)
                            .collect(Collectors.toList());
            List<Map<String, Object>> objects = new ArrayList<>();
            if (StringUtils.hasText(database)) {
                if (!namespaces.contains(database))
                    throw new IllegalArgumentException("数据库／Schema不存在或管理账号不可见");
                for (TableMetadata table :
                        adapters.require(cluster.getSourceType()).metadata().tables(c, database))
                    objects.add(
                            map(
                                    "database",
                                    database,
                                    "tableName",
                                    table.getTableName(),
                                    "resourceType",
                                    isView(table) ? "VIEW" : "TABLE"));
            }
            return map("databases", namespaces, "objects", objects);
        } catch (SQLException e) {
            throw new IllegalArgumentException("管理连接无法盘点资源：" + safeMessage(e), e);
        }
    }

    public Map<String, Object> saveManagement(Long source, AccountRequest input) {
        return locked(
                source,
                () -> {
                    DorisCluster cluster = source(source);
                    Map<String, Object> old = managerRow(source);
                    if (!StringUtils.hasText(input.getUsername()))
                        throw new IllegalArgumentException("管理账号不能为空");
                    String password = input.getPassword();
                    if (password == null || password.isEmpty()) {
                        if (old == null || !input.getUsername().equals(old.get("username")))
                            throw new IllegalArgumentException("更换账号必须提供密码");
                        password = cipher.decrypt(text(old, "password"));
                    }
                    try (Connection c =
                            connections.open(
                                    cluster.getSourceType(),
                                    ConnectionConfig.from(cluster),
                                    null,
                                    input.getUsername(),
                                    password,
                                    false)) {
                        nativeAdapter(cluster).identity(c, input.getUsername());
                    }
                    permissions.upsertManagementAccount(
                            source, input.getUsername(), cipher.encrypt(password));
                    permissions.recordManagementChange(
                            source, operator(), "更新授权管理连接：" + input.getUsername());
                    return map("username", input.getUsername(), "configured", true);
                });
    }

    public Map<String, Object> putMember(
            Long source, String type, String subject, MemberRequest input) {
        return locked(
                source,
                () ->
                        mutateMember(
                                source,
                                type,
                                subject,
                                input.getRoleIds(),
                                input.getExpectedRevision(),
                                null,
                                false));
    }

    public Map<String, Object> bindAccount(
            Long source, String type, String subject, AccountRequest input) {
        return locked(
                source,
                () -> {
                    Map<String, Object> member = member(source, type, subject);
                    List<Long> ids = member == null ? Collections.emptyList() : roleIds(member);
                    return mutateMember(
                            source, type, subject, ids, input.getExpectedRevision(), input, false);
                });
    }

    public void removeMember(Long source, String type, String subject) {
        locked(
                source,
                () -> {
                    Map<String, Object> member = member(source, type, subject);
                    mutateMember(
                            source,
                            type,
                            subject,
                            Collections.emptyList(),
                            member == null ? 0 : number(member.get("revision")),
                            null,
                            true);
                    return null;
                });
    }

    private Map<String, Object> mutateMember(
            Long source,
            String type,
            String subject,
            List<Long> requested,
            Integer expected,
            AccountRequest accountInput,
            boolean remove)
            throws Exception {
        DorisCluster cluster = source(source);
        validateSubject(type, subject, !remove);
        Map<String, Object> old = member(source, type, subject);
        revision(old == null ? map("revision", 0) : old, expected);
        if (requested == null) throw new IllegalArgumentException("角色列表不能为空");
        Set<Long> desiredIds = new LinkedHashSet<>(requested);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Long id : desiredIds) {
            Map<String, Object> role = role(source, id);
            if (!"ACTIVE".equals(role.get("status")))
                throw new IllegalArgumentException("请先完成角色权限配置并核验，再分配成员");
            selected.add(role);
        }
        DatasourceSubjectAccount previous = findAccount(source, type, subject),
                candidate = previous;
        if (accountInput != null) {
            if (!StringUtils.hasText(accountInput.getUsername()))
                throw new IllegalArgumentException("数据库账号不能为空");
            String password = accountInput.getPassword();
            if (password == null || password.isEmpty()) {
                if (previous == null || !previous.getUsername().equals(accountInput.getUsername()))
                    throw new IllegalArgumentException("更换账号必须提供密码");
                password = previous.getPassword();
            }
            for (DatasourceSubjectAccount account : listMappings(source))
                if (accountInput.getUsername().equals(account.getUsername())
                        && !(type.equals(account.getSubjectType())
                                && subject.equals(account.getSubjectId())))
                    throw new IllegalArgumentException("同源其他成员已绑定此账号，请使用独立成员账号以免原生角色相互影响");
            candidate = new DatasourceSubjectAccount();
            candidate.setId(previous == null ? null : previous.getId());
            candidate.setSourceId(source);
            candidate.setSubjectType(type);
            candidate.setSubjectId(subject);
            candidate.setUsername(accountInput.getUsername());
            candidate.setPassword(password);
        }
        if (candidate == null && !remove) {
            final Long memberId = old == null ? null : longNumber(old.get("id"));
            platformTransaction()
                    .execute(
                            tx -> {
                                Long id =
                                        memberId == null
                                                ? permissions.createMember(source, type, subject)
                                                : memberId;
                                saveBindings(id, desiredIds);
                                permissions.markMemberNeedsAccount(id);
                                return null;
                            });
            permissions.recordMemberChange(
                    source, type, subject, operator(), "已保存角色分配，绑定账号后才下发到数据库");
            return memberView(source, member(source, type, subject));
        }
        Long op =
                permissions.createMemberOperation(
                        source,
                        type,
                        subject,
                        operator(),
                        "APPLYING",
                        remove ? "移除数据源成员" : "分配角色／绑定账号");
        NativePermissionAdapter adapter = nativeAdapter(cluster);
        List<Map<String, Object>> steps = new ArrayList<>();
        final DatasourceSubjectAccount saved = candidate;
        boolean attempted = false;
        try {
            if (old != null) permissions.markMemberApplying(old.get("id"));
            List<Map<String, Object>> allRoles = query(permissions.listRoleSnapshots(source));
            Set<String> managed =
                    allRoles.stream()
                            .map(r -> text(r, "nativeName"))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> desired =
                    selected.stream()
                            .map(r -> text(r, "nativeName"))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            final Identity[] verifiedIdentity = {null};
            if (candidate != null) {
                Identity identity;
                boolean legacyUnmanagedRemoval =
                        remove
                                && storedIdentity(old) == null
                                && (old == null || roleIds(old).isEmpty());
                if (legacyUnmanagedRemoval) identity = null;
                else if (remove && storedIdentity(old) != null) identity = storedIdentity(old);
                else
                    try (Connection target = accountConnection(cluster, candidate)) {
                        identity = adapter.identity(target, candidate.getUsername());
                    }
                verifiedIdentity[0] = identity;
                if (identity != null && !managed.isEmpty()) {
                    try (Connection manager = management(source)) {
                        synchronizeMembership(
                                manager,
                                adapter,
                                identity,
                                remove ? Collections.emptySet() : desired,
                                managed,
                                steps,
                                op);
                        Identity oldIdentity = storedIdentity(old);
                        if (oldIdentity == null
                                && previous != null
                                && !previous.getUsername().equals(candidate.getUsername()))
                            try (Connection oldConnection = accountConnection(cluster, previous)) {
                                oldIdentity =
                                        adapter.identity(oldConnection, previous.getUsername());
                            }
                        if (oldIdentity != null && !oldIdentity.equals(identity))
                            synchronizeMembership(
                                    manager,
                                    adapter,
                                    oldIdentity,
                                    Collections.emptySet(),
                                    managed,
                                    steps,
                                    op);
                    }
                }
                if (!remove)
                    try (Connection fresh = accountConnection(cluster, candidate)) {
                        adapter.verifyActiveRoles(fresh, desired);
                    }
            }
            platformTransaction()
                    .execute(
                            tx -> {
                                if (remove) {
                                    if (old != null) {
                                        permissions.clearMemberRoles(old.get("id"));
                                        permissions.deleteMember(old.get("id"));
                                    }
                                    if (previous != null)
                                        accountMapper.deleteById(previous.getId());
                                } else {
                                    Long memberId =
                                            old == null
                                                    ? permissions.createMember(
                                                            source, type, subject)
                                                    : longNumber(old.get("id"));
                                    if (accountInput != null) saveMapping(source, saved);
                                    saveBindings(memberId, desiredIds);
                                    permissions.activateMember(
                                            write(verifiedIdentity[0]), memberId);
                                }
                                permissions.completeOperation(
                                        remove ? "已撤销托管角色并移除平台账号映射" : "角色已下发并核验", write(steps), op);
                                return null;
                            });
        } catch (Exception e) {
            String state = failureState(e, steps, !steps.isEmpty());
            permissions.finishOperation(state, safeMessage(e), write(steps), op);
            if (old != null)
                permissions.setMemberStatus(
                        "FAILED".equals(state) ? old.get("status") : "ERROR", old.get("id"));
            throw new IllegalArgumentException(
                    "操作" + op + " " + state + "：" + safeMessage(e) + "；请核对授权记录，未报告成功");
        }
        return remove ? map("removed", true) : memberView(source, member(source, type, subject));
    }

    private TransactionTemplate platformTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // Per-source GET_LOCK already serializes managed writes. Avoid cross-source
        // next-key gap locks for DELETE(empty grant range) followed by INSERT.
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private Identity storedIdentity(Map<String, Object> member) {
        if (member == null || member.get("nativeIdentity") == null) return null;
        Object value = read(text(member, "nativeIdentity"));
        return value == null ? null : json.convertValue(value, Identity.class);
    }

    private void synchronizeMembership(
            Connection manager,
            NativePermissionAdapter adapter,
            Identity identity,
            Set<String> desired,
            Set<String> managed,
            List<Map<String, Object>> steps,
            Long operation)
            throws SQLException {
        if (!adapter.identityExists(manager, identity)) {
            if (desired.isEmpty()) return;
            throw new IllegalArgumentException("数据库账号已不存在，请重新绑定账号");
        }
        Set<String> current = adapter.memberships(manager, identity),
                currentManaged = new LinkedHashSet<>(current);
        currentManaged.retainAll(managed);
        List<String> changes = new ArrayList<>();
        for (String role : currentManaged)
            if (!desired.contains(role)) changes.add(adapter.membership(role, identity, false));
        for (String role : desired)
            if (!current.contains(role)) changes.add(adapter.membership(role, identity, true));
        String defaults = adapter.defaultRoleSql(manager, identity, managed, desired);
        if (defaults != null) changes.add(defaults);
        for (String sql : changes) {
            Map<String, Object> step = map("sql", sql, "state", "UNKNOWN");
            steps.add(step);
            try {
                execute(manager, sql);
                step.put("state", "SUCCESS");
            } catch (SQLException e) {
                step.put("state", uncertain(e) ? "UNKNOWN" : "FAILED");
                throw e;
            }
            permissions.recordOperationSteps(write(steps), operation);
        }
        Set<String> actual = adapter.memberships(manager, identity);
        actual.retainAll(managed);
        if (!actual.equals(desired)) throw new SQLException("原生成员角色回读与目标不一致");
    }

    /**
     * Legacy HTTP account endpoints use this same mutation path, not a second credential writer.
     */
    public Map<String, Object> inspect(Long source, String type, String subject) {
        DorisCluster cluster = source(source);
        validateSubject(type, subject, false);
        DatasourceSubjectAccount account = findAccount(source, type, subject);
        if (account == null) throw new IllegalArgumentException("尚未绑定数据库账号");
        NativePermissionAdapter adapter = nativeAdapter(cluster);
        String stage = "连接成员数据库账号";
        try (Connection connection = accountConnection(cluster, account)) {
            stage = "验证数据库实际身份";
            Identity identity = adapter.identity(connection, account.getUsername());
            stage = "读取账号原生权限";
            return map(
                    "username",
                    account.getUsername(),
                    "nativeIdentity",
                    adapter.account(identity),
                    "grants",
                    adapter.inspect(connection, identity),
                    "notice",
                    "使用成员绑定账号的新连接读取原生授权；MySQL展开当前连接已激活角色，未激活角色不计为当前有效权限。平台只管理本平台创建的角色，账号可能另有直接授权、外部角色或所有权。");
        } catch (SQLException error) {
            log.warn(
                    "Native permission inspection failed: source={}, subjectType={}, subject={},"
                            + " stage={}, sqlState={}, errorCode={}",
                    source,
                    type,
                    subject,
                    stage,
                    error.getSQLState(),
                    error.getErrorCode());
            throw new IllegalArgumentException(
                    stage
                            + "失败（SQLState="
                            + error.getSQLState()
                            + "，错误码="
                            + error.getErrorCode()
                            + "），请核对成员连接及数据库权限");
        }
    }

    public Map<String, Object> deleteRole(Long source, Long id) {
        return locked(
                source,
                () -> {
                    Map<String, Object> role = role(source, id);
                    if (memberCount(id) > 0) throw new IllegalArgumentException("请先移除或重新分配该角色的成员");
                    Long op = permissions.createRoleOperation(source, id, operator(), "APPLYING");
                    List<Map<String, Object>> steps = new ArrayList<>();
                    boolean attempted = false;
                    try {
                        if (!"DRAFT".equals(role.get("status")))
                            try (Connection c = management(source)) {
                                NativePermissionAdapter adapter = nativeAdapter(source(source));
                                adapter.configure(c);
                                Set<Atom> actual = adapter.readRole(c, text(role, "nativeName"));
                                if (!atoms(role.get("nativeGrants")).containsAll(actual))
                                    throw new IllegalArgumentException("角色存在平台未托管权限，请先核对");
                                List<String> statements =
                                        diff(
                                                adapter,
                                                text(role, "nativeName"),
                                                actual,
                                                Collections.emptySet(),
                                                false);
                                statements.add(adapter.dropRole(text(role, "nativeName")));
                                for (String sql : statements) {
                                    attempted = true;
                                    Map<String, Object> step = map("sql", sql, "state", "UNKNOWN");
                                    steps.add(step);
                                    try {
                                        execute(c, sql);
                                        step.put("state", "SUCCESS");
                                    } catch (SQLException e) {
                                        step.put("state", uncertain(e) ? "UNKNOWN" : "FAILED");
                                        throw e;
                                    }
                                    permissions.recordOperationSteps(write(steps), op);
                                }
                                if (adapter.roleExists(c, text(role, "nativeName")))
                                    throw new SQLException("原生角色删除后仍存在");
                            }
                        platformTransaction()
                                .execute(
                                        tx -> {
                                            permissions.deleteRoleGrants(id);
                                            permissions.deleteRole(id);
                                            permissions.finishRoleDeletion(write(steps), op);
                                            return null;
                                        });
                    } catch (Exception e) {
                        String state = failureState(e, steps, attempted);
                        permissions.finishOperation(state, safeMessage(e), write(steps), op);
                        if (!"FAILED".equals(state)) permissions.markRoleError(id);
                    }
                    return operationView(one(permissions.findOperation(op)));
                });
    }

    public List<Map<String, Object>> operations(Long source) {
        source(source);
        return query(permissions.listOperations(source)).stream()
                .map(this::operationView)
                .collect(Collectors.toList());
    }

    private Set<Atom> expand(
            Connection c,
            Long source,
            NativePermissionAdapter adapter,
            List<GrantSelection> selections)
            throws SQLException {
        if (selections == null) throw new IllegalArgumentException("授权范围不能为空");
        if (selections.size() > 500) throw new IllegalArgumentException("单次最多500项资源范围");
        Set<Atom> result = new LinkedHashSet<>();
        DorisCluster cluster = source(source);
        Set<String> namespaces =
                adapters.require(cluster.getSourceType()).metadata().namespaces(c).stream()
                        .map(Namespace::getName)
                        .collect(Collectors.toSet());
        Map<String, List<TableMetadata>> tables = new HashMap<>();
        for (GrantSelection selection : selections) {
            String db = selection.getDatabase(), kind = selection.getResourceType();
            if (!namespaces.contains(db))
                throw new IllegalArgumentException("库／Schema不存在或不可见：" + db);
            adapter.identifier(db);
            if ("SCHEMA".equals(kind)
                    && selection.getActions() != null
                    && !selection.getActions().isEmpty()) adapter.validateSchemaScope(db);
            if (adapter.doris() && (db.contains(".") || db.contains(":")))
                throw new IllegalArgumentException("当前Doris权限回读不支持含点或冒号的库名");
            if (!Arrays.asList("SCHEMA", "TABLE", "VIEW").contains(kind))
                throw new IllegalArgumentException("资源类型不正确");
            List<TableMetadata> objects =
                    tables.computeIfAbsent(
                            db,
                            k -> adapters.require(cluster.getSourceType()).metadata().tables(c, k));
            if (!"SCHEMA".equals(kind)) {
                boolean found =
                        objects.stream()
                                .anyMatch(
                                        t ->
                                                Objects.equals(
                                                                t.getTableName(),
                                                                selection.getTableName())
                                                        && ("VIEW".equals(kind) == isView(t)));
                if (!found)
                    throw new IllegalArgumentException(
                            "资源不存在或类型不匹配：" + db + "." + selection.getTableName());
                adapter.identifier(selection.getTableName());
                if (adapter.doris()
                        && (selection.getTableName().contains(".")
                                || selection.getTableName().contains(":")))
                    throw new IllegalArgumentException("当前Doris权限回读不支持含点或冒号的表名");
            }
            if (selection.getActions() == null) throw new IllegalArgumentException("操作列表不能为空");
            for (String action : new LinkedHashSet<>(selection.getActions())) {
                List<String> privileges = adapter.nativeActions(action, kind);
                if (adapter.postgres()) result.add(new Atom("SCHEMA", db, null, "USAGE"));
                if (adapter.postgres() && "SCHEMA".equals(kind) && !"CREATE".equals(action)) {
                    for (TableMetadata object : objects)
                        for (String privilege : privileges) {
                            result.add(new Atom("TABLE", db, object.getTableName(), privilege));
                            if ("INSERT".equals(privilege))
                                addSequences(result, adapter, c, db, object.getTableName());
                        }
                } else
                    for (String privilege : privileges) {
                        result.add(
                                new Atom(
                                        "SCHEMA".equals(kind) ? "SCHEMA" : "TABLE",
                                        db,
                                        "SCHEMA".equals(kind) ? null : selection.getTableName(),
                                        privilege));
                        if (adapter.postgres() && "INSERT".equals(privilege))
                            addSequences(result, adapter, c, db, selection.getTableName());
                    }
            }
        }
        return result;
    }

    private void addSequences(
            Set<Atom> result,
            NativePermissionAdapter adapter,
            Connection c,
            String db,
            String table)
            throws SQLException {
        for (Atom atom : adapter.sequences(c, db, table)) {
            result.add(new Atom("SCHEMA", atom.getDatabase(), null, "USAGE"));
            result.add(atom);
        }
    }

    private List<String> diff(
            NativePermissionAdapter adapter,
            String role,
            Set<Atom> before,
            Set<Atom> after,
            boolean create) {
        List<String> result = new ArrayList<>();
        if (create) result.add(adapter.createRole(role));
        for (Atom atom : before)
            if (!after.contains(atom)) result.add(adapter.grantSql(role, atom, false));
        List<Atom> additions = new ArrayList<>(after);
        additions.sort(Comparator.comparing(a -> "SCHEMA".equals(a.getKind()) ? 0 : 1));
        for (Atom atom : additions)
            if (!before.contains(atom)) result.add(adapter.grantSql(role, atom, true));
        return result;
    }

    private boolean readOnly(List<GrantSelection> selections) {
        return selections.stream()
                .allMatch(
                        g ->
                                g.getActions() != null
                                        && g.getActions().stream()
                                                .allMatch(
                                                        a ->
                                                                "QUERY".equals(a)
                                                                        || "SHOW_VIEW".equals(a)));
    }

    private List<GrantSelection> grants(Long id) {
        List<GrantSelection> result = new ArrayList<>();
        for (Map<String, Object> row : query(permissions.listRoleGrants(id))) {
            GrantSelection grant = new GrantSelection();
            grant.setResourceType(text(row, "resourceType"));
            grant.setDatabase(text(row, "databaseName"));
            grant.setTableName(row.get("tableName") == null ? null : text(row, "tableName"));
            grant.setActions(
                    json.convertValue(
                            read(text(row, "actions")), new TypeReference<List<String>>() {}));
            result.add(grant);
        }
        return result;
    }

    private Map<String, Object> roleView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("nativeGrants");
        result.put("grants", grants(longNumber(row.get("id"))));
        result.put("memberCount", memberCount(longNumber(row.get("id"))));
        return result;
    }

    private List<Map<String, Object>> roleViews(Long source) {
        return query(permissions.listRoles(source)).stream()
                .map(this::roleView)
                .collect(Collectors.toList());
    }

    private Map<String, Object> memberView(Long source, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        String type = text(row, "subjectType"), subject = text(row, "subjectId");
        SysUser user = users.selectById(subject);
        result.put(
                "name",
                user == null
                        ? subject
                        : (StringUtils.hasText(user.getNickname())
                                ? user.getNickname()
                                : user.getUsername()));
        if (user != null) result.put("username", user.getUsername());
        result.put("enabled", user != null && Boolean.TRUE.equals(user.getEnabled()));
        result.put("accountType", type);
        List<Long> ids = row.get("id") == null ? Collections.emptyList() : roleIds(row);
        result.put("roleIds", ids);
        DatasourceSubjectAccount account = findAccount(source, type, subject);
        result.put(
                "account",
                map(
                        "username",
                        account == null ? null : account.getUsername(),
                        "configured",
                        account != null));
        result.put("externalAccess", account != null && ids.isEmpty());
        if (account == null && !"ERROR".equals(result.get("status")))
            result.put("status", "NEEDS_ACCOUNT");
        return result;
    }

    private Map<String, Object> operationView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("payload");
        result.remove("expiresAt");
        result.put("operationId", row.get("id"));
        result.put("steps", read(row.get("steps") == null ? "[]" : text(row, "steps")));
        return result;
    }

    private DatasourceSubjectAccount findAccount(Long source, String type, String subject) {
        return listMappings(source).stream()
                .filter(a -> type.equals(a.getSubjectType()) && subject.equals(a.getSubjectId()))
                .findFirst()
                .orElse(null);
    }

    private Connection accountConnection(DorisCluster cluster, DatasourceSubjectAccount account)
            throws SQLException {
        return connections.open(
                cluster.getSourceType(),
                ConnectionConfig.from(cluster),
                null,
                account.getUsername(),
                account.getPassword(),
                "SERVICE".equals(account.getSubjectType()));
    }

    private Connection management(Long source) throws SQLException {
        DorisCluster cluster = source(source);
        Map<String, Object> manager = managerRow(source);
        return connections.open(
                cluster.getSourceType(),
                ConnectionConfig.from(cluster),
                null,
                manager == null ? cluster.getUsername() : text(manager, "username"),
                manager == null ? cluster.getPassword() : cipher.decrypt(text(manager, "password")),
                false);
    }

    private Map<String, Object> managerRow(Long source) {
        return one(permissions.findManagementAccount(source));
    }

    private DorisCluster source(Long id) {
        DorisCluster c = clusters.selectById(id);
        if (c == null) throw new IllegalArgumentException("数据源不存在");
        return c;
    }

    private NativePermissionAdapter nativeAdapter(DorisCluster cluster) {
        return NativePermissionAdapter.forType(cluster.getSourceType());
    }

    private Map<String, Object> role(Long source, Long id) {
        Map<String, Object> r = one(permissions.findRole(id, source));
        if (r == null) throw new IllegalArgumentException("角色不存在或不属于当前数据源");
        return r;
    }

    private Map<String, Object> member(Long source, String type, String subject) {
        return one(permissions.findMember(source, type, subject));
    }

    private List<Long> roleIds(Map<String, Object> member) {
        return query(permissions.listMemberRoleIds(member.get("id"))).stream()
                .map(m -> longNumber(m.get("roleId")))
                .collect(Collectors.toList());
    }

    private long memberCount(Long role) {
        Long n = permissions.countRoleMembers(role);
        return n == null ? 0 : n;
    }

    private void saveBindings(Long member, Set<Long> ids) {
        permissions.clearMemberRoles(member);
        for (Long id : ids) permissions.addMemberRole(member, id);
    }

    private void validateSubject(String type, String subject, boolean requireEnabled) {
        if (!Arrays.asList("USER", "SERVICE").contains(type)
                || !StringUtils.hasText(subject)
                || subject.length() > 191) throw new IllegalArgumentException("无效成员标识");
        final Long key;
        try {
            key = Long.valueOf(subject);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请选择平台已有账号");
        }
        SysUser user = users.selectById(key);
        if (requireEnabled && (user == null || !Boolean.TRUE.equals(user.getEnabled())))
            throw new IllegalArgumentException("账号不存在或已停用");
        if (user != null && ("SERVICE".equals(type) != user.isServiceAccount()))
            throw new IllegalArgumentException("账号类型与授权主体不一致");
    }

    private void validateRoleName(String name) {
        if (!StringUtils.hasText(name) || name.trim().length() > 100)
            throw new IllegalArgumentException("角色名称不能为空且不能超过100字");
    }

    private void revision(Map<String, Object> row, Integer expected) {
        if (expected == null || number(row.get("revision")) != expected)
            throw new PermissionConflictException("配置已变化，请刷新后重试");
    }

    private static boolean isView(TableMetadata table) {
        return table.getTableType() != null
                && table.getTableType().toUpperCase(Locale.ROOT).contains("VIEW");
    }

    private String operator() {
        String id = PrincipalContextHolder.getCurrentUserId();
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("需要已登录管理员");
        return id;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("权限配置序列化失败");
        }
    }

    private Object read(String value) {
        try {
            return json.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("权限记录无法读取");
        }
    }

    private Map<String, Object> object(Object value) {
        return json.convertValue(
                value instanceof String ? read((String) value) : value,
                new TypeReference<Map<String, Object>>() {});
    }

    private Set<Atom> atoms(Object value) {
        return value == null
                ? new LinkedHashSet<>()
                : json.convertValue(
                        value instanceof String ? read((String) value) : value,
                        new TypeReference<LinkedHashSet<Atom>>() {});
    }

    private static String text(Map<String, Object> map, String key) {
        return map.get(key) == null ? "" : String.valueOf(map.get(key));
    }

    private static int number(Object n) {
        return ((Number) n).intValue();
    }

    private static Long longNumber(Object n) {
        return ((Number) n).longValue();
    }

    private static String safeMessage(Exception e) {
        String text = e.getMessage() == null ? "操作失败，请检查管理账号与原生权限" : e.getMessage();
        if (insufficientManagementPrivileges(e)) {
            text =
                    "当前用于权限管理的数据库账号权限不足。请在当前数据源「权限配置 → 授权管理连接」配置专用管理账号，"
                            + "或联系 DBA 补齐该管理账号的系统授权表读取、角色管理及目标对象授权权限。数据库返回："
                            + text;
        }
        return text.substring(0, Math.min(text.length(), 900));
    }

    private static boolean insufficientManagementPrivileges(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                // MySQL/Doris privilege errors and PostgreSQL insufficient_privilege.
                int code = sql.getErrorCode();
                if ("42501".equals(sql.getSQLState())
                        || code == 1044
                        || code == 1142
                        || code == 1143
                        || code == 1227
                        || code == 1370) return true;
            }
        }
        return false;
    }

    private static boolean uncertain(SQLException e) {
        return e instanceof SQLTimeoutException
                || e instanceof SQLRecoverableException
                || e.getSQLState() != null && e.getSQLState().startsWith("08");
    }

    private static String failureState(
            Exception error, List<Map<String, Object>> steps, boolean attempted) {
        if (error instanceof SQLException && uncertain((SQLException) error)) return "UNKNOWN";
        if (steps.stream().anyMatch(s -> "SUCCESS".equals(s.get("state")))) return "PARTIAL";
        return attempted && !(error instanceof SQLException) ? "UNKNOWN" : "FAILED";
    }

    static Map<String, Object> map(Object... args) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) result.put((String) args[i], args[i + 1]);
        return result;
    }

    private interface LockedWork<T> {
        T run() throws Exception;
    }

    private <T> T locked(Long source, LockedWork<T> work) {
        source(source);
        try (Connection lock = platformDataSource.getConnection()) {
            String key = "odw-permissions-" + source;
            try (PreparedStatement statement = lock.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1)
                        throw new PermissionConflictException("该数据源有权限操作正在执行，请稍后重试");
                }
            }
            try {
                return work.run();
            } finally {
                try (PreparedStatement release = lock.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, key);
                    release.executeQuery().close();
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("权限管理失败：" + safeMessage(e));
        }
    }

    private List<Map<String, Object>> query(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> converted = new LinkedHashMap<>();
            row.forEach(
                    (key, value) -> {
                        StringBuilder camel = new StringBuilder();
                        boolean upper = false;
                        for (char ch : key.toCharArray())
                            if (ch == '_') upper = true;
                            else {
                                camel.append(upper ? Character.toUpperCase(ch) : ch);
                                upper = false;
                            }
                        converted.put(camel.toString(), value);
                    });
            result.add(converted);
        }
        return result;
    }

    private Map<String, Object> one(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = query(rows);
        return result.isEmpty() ? null : result.get(0);
    }

    private List<DatasourceSubjectAccount> listMappings(Long source) {
        return accountMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                DatasourceSubjectAccount>()
                        .eq(DatasourceSubjectAccount::getSourceId, source));
    }

    private void saveMapping(Long source, DatasourceSubjectAccount account) {
        validateSubject(account.getSubjectType(), account.getSubjectId(), true);
        account.setSourceId(source);
        if (account.getId() == null) accountMapper.insert(account);
        else accountMapper.updateById(account);
    }
}
