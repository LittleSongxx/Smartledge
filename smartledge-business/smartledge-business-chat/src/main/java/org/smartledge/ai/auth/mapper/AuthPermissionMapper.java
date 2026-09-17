package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.auth.data.AuthPermission;

/**
 * 权限字典 Mapper。
 */
@Mapper
public interface AuthPermissionMapper extends BaseMapper<AuthPermission> {
}
