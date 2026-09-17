package org.smartledge.database.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.smartledge.database.tenant.SmartledgeTenantLineHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
/**
 * @description: 自动配置类
 * @author: Song
 **/

public class MybatisPlusAutoConfiguration {

    @Bean
    public MetaObjectHandler metaObjectHandler(){
        return new MybatisPlusMetaObjectHandler();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(@Value("${app.tenant.enforcement-enabled:true}") boolean tenantEnforcementEnabled) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 租户拦截器必须加在分页拦截器之前：MyBatis-Plus 要求多租户先重写 SQL，分页再基于重写后的语句执行。
        // 它在 SQL 重写层工作，因此同时覆盖 SELECT / INSERT / UPDATE / DELETE —— 已由 SQL 日志实证：
        //   SELECT ... FROM smartledge_document WHERE (status = ?) AND tenant_id = 1
        //   UPDATE smartledge_knowledge_base SET ... WHERE (id = ?) AND tenant_id = 1
        //
        // 上下文传播已在 S21 步骤 1 补齐（见 TenantContext 的传播原语与 application.yaml 的说明），
        // 因此两个链路都能在开启状态下跑通：对话链路与索引构建链路。
        //
        // 注意：本拦截器会对每条语句做「解析 -> 重写 -> 重新序列化」，而运行时 JSqlParser 4.9 的往返
        // 会把 `ORDER BY ... FOR UPDATE` 重排成 MySQL 无法解析的顺序。语句里出现锁定读时必须写成
        // 往返安全的形状（见 SuperAgentDocumentMapper.selectActiveForGraphProjection 与
        // TenantLineStatementRoundTripTest）。
        if (tenantEnforcementEnabled) {
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new SmartledgeTenantLineHandler()));
        }
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
