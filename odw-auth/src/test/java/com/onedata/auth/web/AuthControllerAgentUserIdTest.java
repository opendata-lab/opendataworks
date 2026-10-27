package com.onedata.auth.web;

import com.onedata.auth.context.UserContext;
import com.onedata.auth.user.SysUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthControllerAgentUserIdTest {

    @Test
    void localUsersAreIdentifiedByUsername() {
        UserContext context = new UserContext("1", "alice", "user", UserContext.AUTH_SOURCE_LOCAL);
        assertEquals("local:alice", AuthController.agentUserId(context, null));
    }

    @Test
    void oauthUsersAreIdentifiedBySourceAndExternalId() {
        SysUser user = new SysUser();
        user.setExternalId("sub-42");
        UserContext context = new UserContext("2", "bob", "user", "feishu");
        assertEquals("feishu:sub-42", AuthController.agentUserId(context, user));
    }

    @Test
    void oauthUsersWithoutExternalIdHaveNoAgentIdentity() {
        UserContext context = new UserContext("3", "carol", "user", "feishu");
        assertEquals("", AuthController.agentUserId(context, new SysUser()));
    }
}
