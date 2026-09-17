package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.auth.data.AuthRole;

/**
 * 角色 Mapper。
 */
@Mapper
public interface AuthRoleMapper extends BaseMapper<AuthRole> {
}
