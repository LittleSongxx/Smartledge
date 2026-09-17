package org.smartledge.ai.auth.service;

import org.smartledge.ai.auth.dto.UserLoginRequest;

/**
 * 登录认证服务。
 *
 * <p>凭据校验只有一条实现（本接口的实现类），两个入口的差别只在**要求的能力**与
 * **签发 token 的用途**：</p>
 * <ul>
 *   <li>用户端：要求 {@code chat:use}，签发 {@code chat} 用途 token；</li>
 *   <li>管理端：要求 {@code console:access}（只有管理角色持有），签发 {@code admin} 用途 token。</li>
 * </ul>
 */
public interface UserAuthService {

    /** 用户端登录。 */
    LoginSession loginForChat(UserLoginRequest request);

    /** 管理端登录。 */
    LoginSession loginForAdmin(UserLoginRequest request);
}
