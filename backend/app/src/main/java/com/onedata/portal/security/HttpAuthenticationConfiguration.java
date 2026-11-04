package com.onedata.portal.security;

import com.onedata.auth.aspect.AuthorizationAspect;
import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.jwt.*;
import com.onedata.auth.user.*;
import com.onedata.portal.service.ServiceAccountTokenService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@EnableConfigurationProperties({OdwAuthProperties.class, HttpSecurityProperties.class})
public class HttpAuthenticationConfiguration {
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtService jwtService(OdwAuthProperties p) {
        return new Hs256JwtService(p);
    }

    @Bean
    SysUserService sysUserService(SysUserMapper m, BCryptPasswordEncoder p) {
        return new SysUserService(m, p);
    }

    @Bean
    AuthorizationAspect authorizationAspect() {
        return new AuthorizationAspect();
    }

    @Bean
    FilterRegistrationBean<HttpAuthenticationFilter> authenticationFilter(
            OdwAuthProperties a,
            HttpSecurityProperties h,
            JwtService j,
            SysUserService u,
            ServiceAccountTokenService t) {
        var bean = new FilterRegistrationBean<>(new HttpAuthenticationFilter(a, h, j, u, t));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
