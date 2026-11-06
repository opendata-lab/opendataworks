package com.onedata.portal.service.datasource.adapters.doris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.onedata.portal.service.permission.PermissionModels.Atom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class DorisPermissionAdapterTest {
    // Exact SHOW ROLES / CURRENT_USER() output of Doris 2.0.15 (2026-11-06, local docker).
    @Test
    void readsDoris20PrivilegesWithClusterPrefixAndSpaceSeparatedPrivileges() throws Exception {
        assertEquals(
                Arrays.asList(
                        new Atom("SCHEMA", "odw_chk", null, "SELECT_PRIV"),
                        new Atom("SCHEMA", "odw_chk", null, "LOAD_PRIV"),
                        new Atom("TABLE", "sales", "orders", "SELECT_PRIV")),
                DorisPermissionAdapter.atoms(
                        "internal.default_cluster:odw_chk.*: Select_priv Load_priv ;"
                                + " internal.default_cluster:sales.orders: Select_priv "));
        assertEquals(
                "'odw_admin'@'%'",
                DorisPermissionAdapter.withoutCluster("'default_cluster:odw_admin'@'%'"));
        assertEquals("odw_role", DorisPermissionAdapter.withoutCluster("odw_role"));
    }
}
