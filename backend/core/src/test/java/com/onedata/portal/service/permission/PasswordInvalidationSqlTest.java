package com.onedata.portal.service.permission;
import com.onedata.auth.user.SysUserMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class PasswordInvalidationSqlTest {
    @Test void productionSqlAtomicallyInvalidatesSessionsAndRejectsStalePasswordChanges() throws Exception {
        String sql=String.join(" ",SysUserMapper.class.getMethod("replacePassword",Long.class,String.class,int.class).getAnnotation(Update.class).value()).replaceAll("#\\{[^}]+}","?");
        try(Connection db=DriverManager.getConnection("jdbc:duckdb:")) {
            db.createStatement().execute("CREATE TABLE sys_user(id BIGINT,password_hash VARCHAR,session_version INTEGER,failed_attempts INTEGER,locked_until TIMESTAMP,deleted INTEGER)");
            db.createStatement().execute("INSERT INTO sys_user VALUES(7,'old',3,5,CURRENT_TIMESTAMP,0)");
            try(PreparedStatement update=db.prepareStatement(sql)) {
                update.setString(1,"new");update.setLong(2,7);update.setInt(3,3);assertEquals(1,update.executeUpdate());
                update.setString(1,"stale");assertEquals(0,update.executeUpdate());
            }
            ResultSet r=db.createStatement().executeQuery("SELECT password_hash,session_version,failed_attempts,locked_until FROM sys_user");r.next();assertEquals("new",r.getString(1));assertEquals(4,r.getInt(2));assertEquals(0,r.getInt(3));assertNull(r.getTimestamp(4));
        }
    }
}
