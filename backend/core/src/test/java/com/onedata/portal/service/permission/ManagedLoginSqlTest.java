package com.onedata.portal.service.permission;

import static org.junit.jupiter.api.Assertions.*;

import com.onedata.portal.service.permission.PermissionModels.Identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedLoginSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "DORIS", "POSTGRESQL"})
    void loginDdlQuotesIdentityAndPasswordWithoutClaimingAnExistingAccount(String engine) {
        NativePermissionAdapter adapter = NativePermissionAdapter.forType(engine);
        Identity identity = new Identity("odw'login", adapter.postgres() ? null : "%");
        String create = adapter.createLogin(identity, "Aa1!secret'value");
        assertTrue(create.contains("'Aa1!secret''value'"));
        assertFalse(create.contains("IF NOT EXISTS"));
        if (adapter.postgres()) {
            assertTrue(
                    create.startsWith(
                            "CREATE ROLE \"odw'login\" LOGIN INHERIT NOSUPERUSER NOCREATEDB"
                                    + " NOCREATEROLE"));
            assertEquals("DROP ROLE \"odw'login\"", adapter.dropLogin(identity));
        } else {
            assertTrue(create.startsWith("CREATE USER 'odw''login'@'%' IDENTIFIED BY"));
            assertEquals("DROP USER 'odw''login'@'%'", adapter.dropLogin(identity));
        }
    }

    @org.junit.jupiter.api.Test
    void passwordRedactionPreservesAnUnknownTimeoutOutcome() {
        String password = "Aa1!secret";
        java.sql.SQLException redacted =
                DatasourcePermissionService.credentialFailure(
                        new java.sql.SQLTimeoutException(
                                "CREATE USER PASSWORD '" + password + "' timed out", null, 0),
                        password);
        assertInstanceOf(java.sql.SQLRecoverableException.class, redacted);
        assertNull(redacted.getSQLState());
        assertNull(redacted.getCause());
        assertFalse(redacted.getMessage().contains(password));
        assertTrue(redacted.getMessage().contains("[REDACTED]"));
    }

    @org.junit.jupiter.api.Test
    void passwordRedactionPreservesDefiniteDatabaseErrorDiagnostics() {
        java.sql.SQLException redacted =
                DatasourcePermissionService.credentialFailure(
                        new java.sql.SQLException("denied secret", "42501", 1142), "secret");
        assertEquals("42501", redacted.getSQLState());
        assertEquals(1142, redacted.getErrorCode());
        assertFalse(redacted instanceof java.sql.SQLRecoverableException);
        assertNull(redacted.getCause());
        assertEquals("denied [REDACTED]", redacted.getMessage());
    }

    @org.junit.jupiter.api.Test
    void secretBearingCreateLoginHasABoundedTimeoutAndRedactedOperationSteps() throws Exception {
        var cipher = org.mockito.Mockito.mock(PermissionCredentialCipher.class);
        var permissions =
                org.mockito.Mockito.mock(
                        com.onedata.portal.mapper.DatasourceAccessPermissionMapper.class);
        var service =
                new DatasourcePermissionService(
                        permissions,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        cipher,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        String password = "Aa1!private-secret";
        org.mockito.Mockito.when(cipher.decrypt("encrypted")).thenReturn(password);
        var account = new com.onedata.portal.entity.DatasourceSubjectAccount();
        account.setUsername("managed");
        account.setPassword("encrypted");
        var jdbc =
                new PermissionInspectionJdbc(
                        sql -> PermissionInspectionJdbc.result(new String[] {"User"}));
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        org.mockito.Mockito.when(jdbc.connection.createStatement()).thenReturn(statement);
        java.util.List<java.util.Map<String, Object>> steps = new java.util.ArrayList<>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service,
                "provisionManagedLogin",
                jdbc.connection,
                NativePermissionAdapter.forType("MYSQL"),
                account,
                steps,
                99L);
        var order = org.mockito.Mockito.inOrder(statement);
        order.verify(statement).setQueryTimeout(20);
        order.verify(statement).execute(org.mockito.ArgumentMatchers.contains(password));
        assertEquals("SUCCESS", steps.get(0).get("state"));
        assertFalse(steps.toString().contains(password));
        org.mockito.Mockito.verify(permissions, org.mockito.Mockito.times(2))
                .recordOperationSteps(
                        org.mockito.ArgumentMatchers.argThat(
                                text -> !String.valueOf(text).contains(password)),
                        org.mockito.ArgumentMatchers.eq(99L));
    }

    @org.junit.jupiter.api.Test
    void platformColumnLabelsAcceptUpperSnakeCaseWithoutBreakingCamelCase() {
        var service =
                new DatasourcePermissionService(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("SOURCE_ID", 2L);
        row.put("NATIVE_NAME", "role");
        row.put("subjectId", "7");
        row.put("ID", 1L);
        java.util.List<java.util.Map<String, Object>> normalized =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        service, "query", java.util.List.of(row));
        assertEquals(
                java.util.Map.of("sourceId", 2L, "nativeName", "role", "subjectId", "7", "id", 1L),
                normalized.get(0));
    }
}
