package com.onedata.auth.jwt;

import com.onedata.auth.context.CurrentPrincipal;

/** 会话令牌签发与校验 */
public interface JwtService {

    /** 为用户签发会话 JWT */
    String issue(CurrentPrincipal user);

    /** 校验会话 JWT，成功返回用户上下文，失败（签名/过期/issuer不符）返回 null */
    CurrentPrincipal verify(String token);
}
