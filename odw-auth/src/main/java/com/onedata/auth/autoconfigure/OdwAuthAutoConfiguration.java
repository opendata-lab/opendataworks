package com.onedata.auth.autoconfigure;

import com.onedata.auth.aspect.AuthorizationAspect;
import com.onedata.auth.filter.AuthenticationFilter;
import com.onedata.auth.jwt.Hs256JwtService;
import com.onedata.auth.jwt.JwtService;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.auth.user.SysUserService;
import com.onedata.auth.web.AuthController;
import com.onedata.auth.web.AuthExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证模块自动装配
 * 所有 Bean 均可被宿主以同类型 Bean 覆盖
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "odw.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OdwAuthProperties.class)
@MapperScan("com.onedata.auth.user")
public class OdwAuthAutoConfiguration {

    /**
     * 认证过滤器顺序：晚于宿主的 CORS Filter（HIGHEST_PRECEDENCE）
     */
    public static final int AUTH_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    @ConditionalOnMissingBean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(OdwAuthProperties properties) {
        return new Hs256JwtService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SysUserService sysUserService(SysUserMapper sysUserMapper, BCryptPasswordEncoder passwordEncoder) {
        return new SysUserService(sysUserMapper, passwordEncoder);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthorizationAspect authorizationAspect() {
        return new AuthorizationAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthController authController(SysUserService sysUserService, JwtService jwtService,
                                         OdwAuthProperties properties) {
        return new AuthController(sysUserService, jwtService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthExceptionHandler authExceptionHandler() {
        return new AuthExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "authenticationFilterRegistration")
    public FilterRegistrationBean<AuthenticationFilter> authenticationFilterRegistration(
            OdwAuthProperties properties, JwtService jwtService) {
        FilterRegistrationBean<AuthenticationFilter> bean =
                new FilterRegistrationBean<>(new AuthenticationFilter(properties, jwtService));
        bean.setOrder(AUTH_FILTER_ORDER);
        return bean;
    }
}
