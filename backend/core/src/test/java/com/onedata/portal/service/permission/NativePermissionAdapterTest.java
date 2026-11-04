package com.onedata.portal.service.permission;

import com.onedata.portal.service.permission.PermissionModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePermissionAdapterTest {
    @Test void dorisLoadIsAnIndivisibleNativePrivilegeAndGrantIsNotBusinessWrite() {
        NativePermissionAdapter doris=NativePermissionAdapter.forType("DORIS");
        assertEquals(Collections.singletonList("LOAD_PRIV"),doris.nativeActions("WRITE","TABLE"));
        for(String action:Arrays.asList("INSERT","UPDATE","DELETE","GRANT"))assertThrows(IllegalArgumentException.class,()->doris.nativeActions(action,"TABLE"));
        assertEquals("GRANT LOAD_PRIV ON `internal`.`sales`.`orders` TO ROLE 'odw_role'",doris.grantSql("odw_role",new Atom("TABLE","sales","orders","LOAD_PRIV"),true));
        assertEquals("GRANT 'odw_role' TO 'alice'@'%'",doris.membership("odw_role",new Identity("alice","%"),true));
    }
    @Test void postgresOwnershipIsNotMappedToGrantAlterOrDrop() {
        NativePermissionAdapter pg=NativePermissionAdapter.forType("POSTGRESQL");
        for(String action:Arrays.asList("ALTER","DROP","SHOW_VIEW"))assertThrows(IllegalArgumentException.class,()->pg.nativeActions(action,"TABLE"));
        assertEquals("GRANT USAGE ON SCHEMA \"sales\" TO \"odw_role\"",pg.grantSql("odw_role",new Atom("SCHEMA","sales",null,"USAGE"),true));
        assertEquals("GRANT \"odw_role\" TO \"alice\" WITH INHERIT TRUE",pg.membership("odw_role",new Identity("alice",null),true));
        assertEquals(Arrays.asList("INSERT","UPDATE","DELETE"),pg.nativeActions("WRITE","TABLE"));
    }
    @Test void identifiersRemainIdentifiersAndUnsupportedScopesAreRejected() {
        NativePermissionAdapter mysql=NativePermissionAdapter.forType("MYSQL");
        assertEquals("REVOKE SELECT ON `sales``;DROP DATABASE x`.`orders` FROM 'odw_role'@'%'",mysql.grantSql("odw_role",new Atom("TABLE","sales`;DROP DATABASE x","orders","SELECT"),false));
        assertThrows(IllegalArgumentException.class,()->mysql.nativeActions("CREATE","TABLE"));
        assertThrows(IllegalArgumentException.class,()->mysql.nativeActions("ALTER","VIEW"));
        assertThrows(IllegalArgumentException.class,()->NativePermissionAdapter.literal("bad\\account"));
    }
    @Test void mysqlDatabaseWildcardsAreEscapedForExactSchemaAuthorization() {
        NativePermissionAdapter mysql=NativePermissionAdapter.forType("MYSQL");
        assertEquals("GRANT SELECT ON `sales\\_east\\%`.* TO 'odw_role'@'%'",mysql.grantSql("odw_role",new Atom("SCHEMA","sales_east%",null,"SELECT"),true));
        assertThrows(IllegalArgumentException.class,()->mysql.validateSchemaScope("sales_east%"));
        assertDoesNotThrow(()->mysql.validateSchemaScope("sales"));
    }
}
