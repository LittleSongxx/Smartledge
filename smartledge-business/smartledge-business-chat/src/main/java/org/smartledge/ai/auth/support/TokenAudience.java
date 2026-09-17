package org.smartledge.ai.auth.support;

/**
 * token 用途。用户端与管理端签发不同用途的 token，**用途之间不通用**。
 *
 * <p>这不是"同一身份的两个入口"：一个账号可以在自己的租户里既有对话能力又有管理能力，
 * 但两端拿到的 token 用途不同，因此用户端泄露的 token 不能直接用于管理接口。
 * 管理接口在 filter chain 上要求 {@link #ADMIN} 用途；对话接口接受已认证的任意用途
 * （管理员本身也是租户内的用户，天然可以对话）。</p>
 */
public enum TokenAudience {

    /** 用户端对话 token。 */
    CHAT("chat"),

    /** 管理端 token。 */
    ADMIN("admin");

    private final String claimValue;

    TokenAudience(String claimValue) {
        this.claimValue = claimValue;
    }

    /** 写进 JWT {@code aud} 声明的值。 */
    public String claimValue() {
        return claimValue;
    }

    /** 解析 {@code aud} 声明；未知值返回 {@code null}，由调用方按"token 无效"处理。 */
    public static TokenAudience fromClaim(String value) {
        if (value == null) {
            return null;
        }
        for (TokenAudience audience : values()) {
            if (audience.claimValue.equals(value)) {
                return audience;
            }
        }
        return null;
    }
}
