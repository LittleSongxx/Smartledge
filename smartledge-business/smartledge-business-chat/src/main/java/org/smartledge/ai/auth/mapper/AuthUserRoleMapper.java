package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.auth.data.AuthUserRole;

/**
 * 用户角色关联 Mapper。
 */
@Mapper
public interface AuthUserRoleMapper extends BaseMapper<AuthUserRole> {
}
