package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.ServiceAccountToken;

import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ServiceAccountTokenMapper extends BaseMapper<ServiceAccountToken> {
    @Select("SELECT * FROM service_account_token WHERE user_id=#{userId} ORDER BY id DESC")
    List<ServiceAccountToken> listByUser(Long userId);

    @Select(
            "SELECT t.user_id FROM service_account_token t JOIN sys_user u ON u.id=t.user_id WHERE"
                + " t.token_hash=#{hash} AND t.revoked_at IS NULL AND t.expires_at>UTC_TIMESTAMP()"
                + " AND u.account_type='SERVICE' AND u.auth_source='service' AND u.enabled=1 AND"
                + " u.deleted=0")
    Long authenticate(String hash);

    @Update(
            "UPDATE service_account_token SET last_used_at=UTC_TIMESTAMP() WHERE token_hash=#{hash}"
                + " AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()")
    int touchLastUsedAt(String hash);

    @Select(
            "SELECT user_id, SUM(CASE WHEN revoked_at IS NULL AND expires_at>UTC_TIMESTAMP() THEN 1"
                + " ELSE 0 END) AS active_count, MAX(last_used_at) AS last_used_at FROM"
                + " service_account_token GROUP BY user_id")
    List<java.util.Map<String, Object>> tokenStats();

    @Update(
            "UPDATE service_account_token SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP()) WHERE"
                + " id=#{id} AND user_id=#{userId}")
    int revoke(@Param("userId") Long userId, @Param("id") Long id);
}
