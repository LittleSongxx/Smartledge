package org.smartledge.ai.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthPropertiesTest {

    @Test
    @DisplayName("签名密钥只来自本地/环境配置，不受系统参数快照影响")
    void tokenSecretIgnoresTenantWritableSnapshot() {
        AdminAuthProperties properties = new AdminAuthProperties();
        properties.setTokenSecret("env-local-secret");
        properties.setTokenExpireMinutes(30L);

        SystemConfigSnapshot snapshot = SystemConfigSnapshot.defaults();
        snapshot.getAdminAuth().setTokenSecret("forged-from-config-write");
        snapshot.getAdminAuth().setTokenExpireMinutes(720L);

        properties.bindSystemConfigProvider(providerOf(() -> snapshot));

        assertThat(properties.getTokenSecret()).isEqualTo("env-local-secret");
        assertThat(properties.getTokenExpireMinutes()).isEqualTo(720L);
    }

    private static ObjectProvider<SystemConfigProvider> providerOf(SystemConfigProvider delegate) {
        return new ObjectProvider<>() {
            @Override
            public SystemConfigProvider getObject() {
                return delegate;
            }

            @Override
            public SystemConfigProvider getObject(Object... args) {
                return delegate;
            }

            @Override
            public SystemConfigProvider getIfAvailable() {
                return delegate;
            }

            @Override
            public SystemConfigProvider getIfUnique() {
                return delegate;
            }
        };
    }
}
