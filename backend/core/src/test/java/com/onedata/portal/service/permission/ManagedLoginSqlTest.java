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
}
