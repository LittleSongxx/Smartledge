package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.auth.data.AuthRolePermission;

/**
 * 角色权限关联 Mapper。
 */
@Mapper
public interface AuthRolePermissionMapper extends BaseMapper<AuthRolePermission> {
}
