package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.auth.data.AuthTenant;

/**
 * 租户 Mapper。
 */
@Mapper
public interface AuthTenantMapper extends BaseMapper<AuthTenant> {
}
