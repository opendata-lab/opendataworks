package com.onedata.auth.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** sys_user Mapper */
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Update(
            "UPDATE sys_user SET password_hash=#{hash}, session_version=session_version+1,"
                + " failed_attempts=0, locked_until=NULL WHERE id=#{id} AND deleted=0 AND"
                + " session_version=#{version}")
    int replacePassword(
            @Param("id") Long id, @Param("hash") String hash, @Param("version") int version);

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM sys_user WHERE username=#{username}")
    long countAllByUsername(String username);

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND enabled=1 AND role='admin'")
    long countActiveAdmins();

    @org.apache.ibatis.annotations.Select(
            "SELECT id FROM sys_user WHERE deleted=0 AND role='admin' ORDER BY id FOR UPDATE")
    java.util.List<Long> lockActiveAdminIds();

    @org.apache.ibatis.annotations.Select("SELECT id FROM sys_user WHERE id=#{id} FOR UPDATE")
    Long lockUserById(Long id);

    @Update("UPDATE sys_user SET session_version=session_version+1 WHERE id=#{id} AND deleted=0")
    int incrementSessionVersion(Long id);

    @Update(
            "UPDATE sys_user SET"
                + " password_hash=#{hash},session_version=session_version+1,failed_attempts=0,locked_until=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} AND deleted=0")
    int updatePasswordAndInvalidateSession(@Param("id") Long id, @Param("hash") String hash);

    @Update(
            "UPDATE sys_user SET"
                + " deleted=1,enabled=0,session_version=session_version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} AND deleted=0")
    int softDeleteAndInvalidateSession(Long id);
}
