package org.smartledge.ai.manage.config;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签名密钥不得再作为租户可写系统参数。
 */
class SystemConfigRegistryTokenSecretIsolationTest {

    private final SystemConfigRegistry registry = new SystemConfigRegistry();

    @Test
    @DisplayName("参数目录不再登记 adminAuth.tokenSecret")
    void tokenSecretIsNotAWritableDefinition() {
        assertThat(registry.definitions().stream().map(SystemConfigRegistry.ConfigDefinition::key))
            .doesNotContain("adminAuth.tokenSecret")
            .contains("adminAuth.tokenExpireMinutes");
    }

    @Test
    @DisplayName("租户管理员更新配置不能改 token 签名密钥")
    void applyingTokenSecretIsRejected() {
        SystemConfigSnapshot snapshot = SystemConfigSnapshot.defaults();
        snapshot.getAdminAuth().setTokenExpireMinutes(720L);
        snapshot.getAdminAuth().setTokenSecret("should-not-be-writable");

        assertThatThrownBy(() -> registry.apply(snapshot, "adminAuth.tokenSecret",
            TextNode.valueOf("forged-secret-from-tenant-admin")))
            .isInstanceOf(SystemConfigValidationException.class)
            .hasMessageContaining("不支持的配置项");
    }
}
