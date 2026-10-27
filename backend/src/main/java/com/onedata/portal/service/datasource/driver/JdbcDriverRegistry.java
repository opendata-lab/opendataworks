package com.onedata.portal.service.datasource.driver;

import org.springframework.stereotype.Component;

import java.sql.Driver;

/**
 * 仅检查内置驱动；可选 manifest、driverId 和隔离加载器留待后续实现。
 */
@Component
public class JdbcDriverRegistry {

    public String status(String driverClass) {
        try {
            return Driver.class.isAssignableFrom(Class.forName(driverClass)) ? "AVAILABLE" : "INVALID_DRIVER_CLASS";
        } catch (ClassNotFoundException | LinkageError e) {
            return "DRIVER_NOT_INSTALLED";
        }
    }

    public void requireAvailable(String driverClass) {
        String status = status(driverClass);
        if (!"AVAILABLE".equals(status)) {
            throw new IllegalStateException(status + ": " + driverClass);
        }
    }
}
