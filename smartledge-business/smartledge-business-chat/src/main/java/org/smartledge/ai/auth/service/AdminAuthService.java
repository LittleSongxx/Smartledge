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

    /** 作废当前登录 token（升高 token 版本）。 */
    void logout();
}
