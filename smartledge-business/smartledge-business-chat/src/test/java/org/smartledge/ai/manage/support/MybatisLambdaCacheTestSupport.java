package org.smartledge.ai.manage.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单元测试用的 MyBatis-Plus 实体元数据初始化。
 *
 * <p>{@code LambdaQueryWrapper} 依赖实体的 {@code TableInfo} 缓存，而该缓存平时由 MyBatis 映射器扫描建立。
 * 纯单元测试没有 Spring 上下文，因此必须显式初始化，否则解析方法引用时会抛
 * {@code MybatisPlus can not find lambda cache for this entity}。</p>
 */
public final class MybatisLambdaCacheTestSupport {

    private MybatisLambdaCacheTestSupport() {
    }

    public static void initialize(Class<?>... entities) {
        for (Class<?> entity : entities) {
            // MapperBuilderAssistant 的 namespace 只能设置一次，因此每个实体单独构建一个。
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
