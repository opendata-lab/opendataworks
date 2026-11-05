package com.onedata.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.user.SysUser;

import org.junit.jupiter.api.Test;

class AuthControllerAgentUserIdTest {

    @Test
    void localUsersAreIdentifiedByUsername() {
        CurrentPrincipal context =
                new CurrentPrincipal("1", "alice", "user", CurrentPrincipal.AUTH_SOURCE_LOCAL);
        assertEquals("local:alice", AuthController.agentUserId(context, null));
    }

    @Test
    void oauthUsersAreIdentifiedBySourceAndExternalId() {
        SysUser user = new SysUser();
        user.setExternalId("sub-42");
        CurrentPrincipal context = new CurrentPrincipal("2", "bob", "user", "feishu");
        assertEquals("feishu:sub-42", AuthController.agentUserId(context, user));
    }

    @Test
    void oauthUsersWithoutExternalIdHaveNoAgentIdentity() {
        CurrentPrincipal context = new CurrentPrincipal("3", "carol", "user", "feishu");
        assertEquals("", AuthController.agentUserId(context, new SysUser()));
    }
}
