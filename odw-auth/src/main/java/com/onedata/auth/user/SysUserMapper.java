package com.onedata.auth.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * sys_user Mapper
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Update("UPDATE sys_user SET password_hash=#{hash}, session_version=session_version+1, "
            + "failed_attempts=0, locked_until=NULL WHERE id=#{id} AND deleted=0 AND session_version=#{version}")
    int replacePassword(@Param("id") Long id, @Param("hash") String hash, @Param("version") int version);
}
