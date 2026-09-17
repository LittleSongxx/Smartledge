package org.smartledge.ai.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.smartledge.ai.knowledge.augmentation.service.impl.RaptorBuildServiceImpl;
import org.smartledge.core.SpringUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息层与事务边界的架构不变量。
 *
 * <p>这些规则把 S19 修复的缺陷固化为可自动检验的约束，避免在后续改动中被无声地退回：
 * <ul>
 *   <li>Kafka 已经删除，不得再被引入；</li>
 *   <li>资源前缀只能由消息拓扑类解析一次；</li>
 *   <li>数据库事务方法内不得调用外部端口；</li>
 *   <li>RAPTOR 构建方法不得再挂事务注解。</li>
 * </ul>
 */
class MessagingBoundaryArchTest {

    private static JavaClasses businessClasses;

    @BeforeAll
    static void importClasses() {
        businessClasses = new ClassFileImporter().importPackages("org.smartledge.ai");
    }

    @Test
    @DisplayName("业务代码不再依赖 Kafka")
    void kafkaIsFullyRemoved() {
        ArchRule rule = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.kafka..", "org.apache.kafka..");
        rule.check(businessClasses);
    }

    @Test
    @DisplayName("资源前缀只由消息拓扑类解析，避免多处各自计算名字")
    void resourcePrefixIsResolvedByMessagingTopologyOnly() {
        ArchRule rule = noClasses()
            .that()
            .haveSimpleNameNotEndingWith("DocumentMessagingTopology")
            .should()
            .callMethod(SpringUtil.class, "getPrefixDistinctionName");
        rule.check(businessClasses);
    }

    @Test
    @DisplayName("事务方法内不得引用 Python 工具箱客户端")
    void transactionalMethodsDoNotTouchRagToolsClient() {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : businessClasses) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isAnnotatedWith(Transactional.class)) {
                    continue;
                }
                for (JavaAccess<?> access : method.getAccessesFromSelf()) {
                    String target = access.getTargetOwner().getFullName();
                    if (target.startsWith("org.smartledge.ai.ragtools")) {
                        violations.add(method.getFullName() + " -> " + target);
                    }
                }
            }
        }
        assertThat(violations)
            .as("数据库事务方法内出现外部工具箱调用，会持有连接与锁直到外部调用返回")
            .isEmpty();
    }

    @Test
    @DisplayName("RAPTOR 构建方法不再挂事务注解，外部调用必须在事务之外")
    void raptorBuildMethodsAreNotTransactional() {
        ArchRule rule = noMethods()
            .that()
            .areDeclaredIn(RaptorBuildServiceImpl.class)
            .and()
            .haveNameMatching("rebuild.*")
            .should()
            .beAnnotatedWith(Transactional.class);
        rule.check(businessClasses);
    }
}
