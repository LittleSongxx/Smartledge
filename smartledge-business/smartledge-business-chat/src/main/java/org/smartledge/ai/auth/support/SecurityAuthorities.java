package org.smartledge.ai.auth.support;

import java.util.Objects;

/**
 * Spring Security 授权用的 authority 命名（全仓库唯一来源）。
 *
 * <p>两类 authority 语义不同，不能混用：</p>
 * <ul>
 *   <li><b>用途</b>（{@link #AUDIENCE_ADMIN} / {@link #AUDIENCE_CHAT}）：由 token 的 {@code aud} 决定，
 *       用于把两个入口隔开 —— 用户端 token 进不了 {@code /manage/**}。</li>
 *   <li><b>权限</b>（{@link #permission(String)}）：登录时从角色权限表解析并写进 token，
 *       用于接口级判定。</li>
 * </ul>
 */
public final class SecurityAuthorities {

    /** 管理端用途。 */
    public static final String AUDIENCE_ADMIN = "AUD_ADMIN";

    /** 用户端用途。 */
    public static final String AUDIENCE_CHAT = "AUD_CHAT";

    private static final String PERMISSION_PREFIX = "PERM_";

    private SecurityAuthorities() {
    }

    /** token 用途对应的 authority。 */
    public static String audience(TokenAudience audience) {
        Objects.requireNonNull(audience, "audience");
        return audience == TokenAudience.ADMIN ? AUDIENCE_ADMIN : AUDIENCE_CHAT;
    }

    /** 权限编码对应的 authority。 */
    public static String permission(String permissionCode) {
        return PERMISSION_PREFIX + permissionCode;
    }
}
