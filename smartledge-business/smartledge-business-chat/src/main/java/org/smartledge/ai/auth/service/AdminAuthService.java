package org.smartledge.ai.auth.service;

import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.vo.AdminLoginVo;
import org.smartledge.ai.auth.vo.AdminProfileVo;

/**
 * 管理端登录认证服务。
 */
public interface AdminAuthService {

    AdminLoginVo login(UserLoginRequest request);

    AdminProfileVo currentProfile();
}
