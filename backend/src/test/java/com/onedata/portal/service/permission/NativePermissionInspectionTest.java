package com.onedata.portal.service.permission;

import com.onedata.portal.service.permission.PermissionModels.Identity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.onedata.portal.service.permission.PermissionInspectionJdbc.result;
import static org.junit.jupiter.api.Assertions.*;

class NativePermissionInspectionTest {
    @Test void mysqlDirectGrantsNeedNoSystemTableAccess() throws Exception {
        PermissionInspectionJdbc jdbc = new PermissionInspectionJdbc(sql -> {
            if (sql.contains("information_schema.ENABLED_ROLES")) return result(new String[]{"name", "host"});
            assertEquals("SHOW GRANTS FOR CURRENT_USER", sql);
            return result(new String[]{"Grants"}, new Object[]{"GRANT SELECT ON `sales`.`orders` TO 'reader'@'localhost'"});
        });
        List<String> grants = NativePermissionAdapter.forType("MYSQL").inspect(jdbc.connection, new Identity("reader", "localhost"));
        assertEquals(2, jdbc.queries.size());
        assertTrue(grants.get(0).contains("`sales`.`orders`"));
        assertTrue(jdbc.queries.stream().noneMatch(sql -> sql.contains("mysql.")));
    }

    @Test void mysqlExpandsOnlyEnabledRolesAndPreservesTheirHostAndEscaping() throws Exception {
        PermissionInspectionJdbc jdbc = new PermissionInspectionJdbc(sql -> {
            if (sql.contains("information_schema.ENABLED_ROLES")) return result(new String[]{"name", "host"},
                    new Object[]{"sales_ro", "%"}, new Object[]{"crm'ro", "corp'host"});
            assertEquals("SHOW GRANTS FOR CURRENT_USER USING 'sales_ro'@'%', 'crm''ro'@'corp''host'", sql);
            return result(new String[]{"Grants"}, new Object[]{"GRANT SELECT ON `crm`.`customers` TO 'reader'@'%'"});
        });
        List<String> grants = NativePermissionAdapter.forType("MYSQL").inspect(jdbc.connection, new Identity("reader", "%"));
        assertTrue(grants.get(0).contains("`crm`.`customers`"));
        assertTrue(jdbc.queries.stream().noneMatch(sql -> sql.contains("APPLICABLE_ROLES") || sql.contains("SET ROLE")));
    }

    @Test void dorisReadsOwnGrantsAndOmitsPasswordColumns() throws Exception {
        PermissionInspectionJdbc jdbc = new PermissionInspectionJdbc(sql -> {
            assertEquals("SHOW GRANTS", sql);
            return result(new String[]{"UserIdentity", "Password", "TablePrivs"},
                    new Object[]{"'reader'@'%'", "PRIVATE", "internal.sales.orders: Select_priv"});
        });
        List<String> grants = NativePermissionAdapter.forType("DORIS").inspect(jdbc.connection, new Identity("reader", "%"));
        assertTrue(grants.get(0).contains("Select_priv"));
        assertFalse(grants.get(0).contains("PRIVATE"));
        assertFalse(grants.get(0).contains("password"));
    }
}
