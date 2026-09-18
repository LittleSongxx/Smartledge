package org.smartledge.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密钥兜底隔离守卫：主 application.yaml 的占位符一律不允许带默认口令
 * （带默认口令等于对任何非本机部署裸奔）；本地默认只允许出现在 application-local.yaml。
 */
class SecretDefaultsProfileIsolationTest {

    /** 与 deploy/docker-compose 本地栈一致的开发口令；主配置里出现即失败。 */
    private static final String[] BANNED_DEFAULTS = {"smartledge", "smartledge123", "smartledge2026", "elastic"};

    @Test
    @DisplayName("主配置的密钥占位符没有默认口令兜底")
    void mainConfigHasNoSecretDefaults() {
        List<String> violations = new ArrayList<>();
        walk(load("application.yaml"), "", violations);

        assertThat(violations)
            .as("主配置不允许任何密钥默认值，本地默认只放 application-local.yaml；发现违规键: %s", violations)
            .isEmpty();
    }

    @Test
    @DisplayName("local profile 承载本地依赖栈默认口令，本机开发仍可一键启动")
    void localProfileCarriesDevDefaults() {
        Map<String, Object> local = load("application-local.yaml");

        assertThat(nested(local, "spring", "data", "redis", "password")).isEqualTo("smartledge");
        assertThat(nested(local, "spring", "datasource", "password")).isEqualTo("smartledge");
        assertThat(nested(local, "spring", "rabbitmq", "password")).isEqualTo("smartledge");
        assertThat(nested(local, "app", "manage", "minio", "secret-key")).isEqualTo("smartledge123");
        assertThat(nested(local, "app", "manage", "pgvector", "password")).isEqualTo("smartledge");
        assertThat(nested(local, "app", "manage", "elasticsearch", "password")).isEqualTo("elastic");
    }

    private static Map<String, Object> load(String name) {
        try (InputStream input = SecretDefaultsProfileIsolationTest.class
            .getClassLoader().getResourceAsStream(name)) {
            return new Yaml().load(input);
        }
        catch (Exception exception) {
            throw new IllegalStateException("读取 " + name + " 失败", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, String path, List<String> violations) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                walk(entry.getValue(), path.isBlank()
                    ? String.valueOf(entry.getKey())
                    : path + "." + entry.getKey(), violations);
            }
            return;
        }
        String value = String.valueOf(node);
        for (String banned : BANNED_DEFAULTS) {
            // 形如 ${VAR:默认} 的兜底才会把开发口令带进生产；纯 ${VAR} 占位是期望行为。
            if (value.contains(":") && value.contains("${") && value.contains(":" + banned + "}")) {
                violations.add(path + " -> " + value);
            }
            if (value.equals(banned)) {
                violations.add(path + " -> 明文口令 " + value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
