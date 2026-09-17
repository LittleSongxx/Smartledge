package org.smartledge.ai.knowledge.augmentation.model.graph;

import java.util.List;
import java.util.Map;

public final class GraphRagEvaluationBaselineSuites {

    public static final String O6_LLM_NER_BATCH_ID = "o6-llm-ner-real-document-baseline";

    public static final String O6_LLM_NER_BATCH_NAME = "O6 GraphRAG LLM/NER 真实文档 baseline";

    public static final String O6_CROSS_DOCUMENT_BATCH_ID = "o6-cross-document-graph-baseline";

    public static final String O6_CROSS_DOCUMENT_BATCH_NAME = "O6 GraphRAG 跨文档图谱 baseline";

    public static final String SOURCE_PRODUCTION_RELEASE =
        "需要的例子演示/生产环境发布与回滚操作规范.md";

    public static final String SOURCE_CUSTOMER_DATA_ACCESS =
        "需要的例子演示/客户数据分级与访问控制管理制度.md";

    public static final String SOURCE_O6_AUDIT_EVIDENCE =
        "需要的例子演示/O6跨文档图谱-审计证据规范A.md";

    public static final String SOURCE_O6_AUDIT_ALIAS =
        "需要的例子演示/O6跨文档图谱-审计系统别名说明B.md";

    private GraphRagEvaluationBaselineSuites() {
    }

    public static List<GraphRagEvaluationSuite> o6LlmNerBaselineTemplates() {
        return o6LlmNerBaseline(null, null);
    }

    public static List<GraphRagEvaluationSuite> o6LlmNerBaseline(Long documentId, Long taskId) {
        return List.of(
            productionOwnerTriggersRollback(documentId, taskId),
            productionSreExecutesTrafficSwitch(documentId, taskId),
            productionDbaExecutesDatabaseScript(documentId, taskId),
            customerDataL4Approval(documentId, taskId),
            customerDataAuditTrailRecordsPermissionFlow(documentId, taskId),
            customerDataAdminRevokesAbnormalPermission(documentId, taskId),
            customerDataStoresInVaultDocs(documentId, taskId),
            customerDataStoresInDataCleanRoom(documentId, taskId)
        );
    }

    public static List<GraphRagEvaluationSuite> o6LlmNerBaselineBySource(
        Map<String, GraphRagEvaluationSourceBinding> sourceBindings
    ) {
        GraphRagEvaluationSourceBinding productionRelease = binding(sourceBindings, SOURCE_PRODUCTION_RELEASE);
        GraphRagEvaluationSourceBinding customerDataAccess = binding(sourceBindings, SOURCE_CUSTOMER_DATA_ACCESS);
        return List.of(
            productionOwnerTriggersRollback(documentId(productionRelease), taskId(productionRelease)),
            productionSreExecutesTrafficSwitch(documentId(productionRelease), taskId(productionRelease)),
            productionDbaExecutesDatabaseScript(documentId(productionRelease), taskId(productionRelease)),
            customerDataL4Approval(documentId(customerDataAccess), taskId(customerDataAccess)),
            customerDataAuditTrailRecordsPermissionFlow(documentId(customerDataAccess), taskId(customerDataAccess)),
            customerDataAdminRevokesAbnormalPermission(documentId(customerDataAccess), taskId(customerDataAccess)),
            customerDataStoresInVaultDocs(documentId(customerDataAccess), taskId(customerDataAccess)),
            customerDataStoresInDataCleanRoom(documentId(customerDataAccess), taskId(customerDataAccess))
        );
    }

    private static GraphRagEvaluationSourceBinding binding(Map<String, GraphRagEvaluationSourceBinding> sourceBindings,
                                                           String sourceDocument) {
        if (sourceBindings == null) {
            return null;
        }
        return sourceBindings.get(sourceDocument);
    }

    private static Long documentId(GraphRagEvaluationSourceBinding binding) {
        return binding == null ? null : binding.getDocumentId();
    }

    private static Long taskId(GraphRagEvaluationSourceBinding binding) {
        return binding == null ? null : binding.getTaskId();
    }

    private static GraphRagEvaluationSuite productionOwnerTriggersRollback(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-prod-release-owner-triggers-rollback")
            .name("生产发布负责人触发回滚")
            .scenario("O1-06 实体关系 / O6 LLM/NER 抽取")
            .question("发布负责人和强制回滚是什么关系？")
            .sourceDocument(SOURCE_PRODUCTION_RELEASE)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "release", "relation", "trigger", "weak-evidence"))
            .expectedEntities(List.of(
                entity("发布负责人", List.of("对应研发团队"), List.of()),
                entity("回滚", List.of("强制回滚", "人工回滚"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("发布负责人")
                .targetName("回滚")
                .relationType("触发")
                .relationTypeAliases(List.of("TRIGGERS"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("发布负责人", "回滚"))
                .sourceName("发布负责人")
                .targetName("回滚")
                .relationType("触发")
                .relationTypeAliases(List.of("TRIGGERS"))
                .build()))
            .forbiddenRelations(List.of(
                forbiddenRelation(
                    "智能客服平台",
                    "GitLab",
                    "DEPENDS_ON",
                    "适用系统或核心平台清单只能证明弱关联，不能直接升级成强 DEPENDS_ON。"
                ),
                forbiddenRelation(
                    "智能客服平台",
                    "CanaryHub",
                    "DEPENDS_ON",
                    "适用系统或核心平台清单只能证明弱关联，不能直接升级成强 DEPENDS_ON。"
                )
            ))
            .build();
    }

    private static GraphRagEvaluationSuite productionSreExecutesTrafficSwitch(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-prod-release-sre-executes-traffic-switch")
            .name("值班 SRE 执行流量切换")
            .scenario("O6 制度文档角色职责抽取")
            .question("值班 SRE 在回滚流程中执行什么动作？")
            .sourceDocument(SOURCE_PRODUCTION_RELEASE)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "release", "role", "execute", "weak-evidence"))
            .expectedEntities(List.of(
                entity("值班 SRE", List.of("SRE 团队"), List.of("SRE 团队")),
                entity("流量切换", List.of("流量切回旧版本"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("值班 SRE")
                .targetName("流量切换")
                .relationType("执行")
                .relationTypeAliases(List.of("EXECUTES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("SRE", "流量切换"))
                .sourceName("值班 SRE")
                .targetName("流量切换")
                .relationType("执行")
                .relationTypeAliases(List.of("EXECUTES"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite productionDbaExecutesDatabaseScript(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-prod-release-dba-executes-database-script")
            .name("DBA 执行数据库脚本")
            .scenario("O6 制度文档角色职责抽取")
            .question("DBA 在发布流程中负责执行什么脚本？")
            .sourceDocument(SOURCE_PRODUCTION_RELEASE)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "release", "role", "execute", "alias", "weak-evidence"))
            .expectedEntities(List.of(
                entity("DBA", List.of("DBA 团队"), List.of("DBA 团队")),
                entity("数据库脚本", List.of("数据库执行脚本", "回滚脚本"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("DBA")
                .targetName("数据库脚本")
                .relationType("执行")
                .relationTypeAliases(List.of("EXECUTES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("DBA", "执行", "数据库脚本"))
                .sourceName("DBA")
                .targetName("数据库脚本")
                .relationType("执行")
                .relationTypeAliases(List.of("EXECUTES"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite customerDataL4Approval(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-customer-data-l4-approval")
            .name("L4 数据三级审批")
            .scenario("O6 数据治理制度关系抽取")
            .question("L4 数据权限需要哪些角色审批？")
            .sourceDocument(SOURCE_CUSTOMER_DATA_ACCESS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "security", "approval", "alias", "weak-evidence"))
            .expectedEntities(List.of(
                entity("L4 数据", List.of("高敏感信息"), List.of("高敏感信息")),
                entity("信息安全部", List.of(), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("L4 数据")
                .targetName("信息安全部")
                .relationType("审批")
                .relationTypeAliases(List.of("APPROVES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("L4", "信息安全部", "审批"))
                .sourceName("L4 数据")
                .targetName("信息安全部")
                .relationType("审批")
                .relationTypeAliases(List.of("APPROVES"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite customerDataAuditTrailRecordsPermissionFlow(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-customer-data-audittrail-records-permissions")
            .name("AuditTrail 记录权限流转")
            .scenario("O6 英文系统名与中文制度关系抽取")
            .question("AuditTrail 记录哪些权限相关行为？")
            .sourceDocument(SOURCE_CUSTOMER_DATA_ACCESS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "security", "system", "records", "weak-evidence"))
            .expectedEntities(List.of(
                entity("AuditTrail", List.of(), List.of()),
                entity("权限申请", List.of("审批", "回收", "延长"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("AuditTrail")
                .targetName("权限申请")
                .relationType("记录")
                .relationTypeAliases(List.of("RECORDS"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("AuditTrail", "权限申请", "审批", "回收"))
                .sourceName("AuditTrail")
                .targetName("权限申请")
                .relationType("记录")
                .relationTypeAliases(List.of("RECORDS"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite customerDataAdminRevokesAbnormalPermission(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-customer-data-admin-revokes-abnormal-permission")
            .name("系统管理员回收异常权限")
            .scenario("O6 数据治理职责动作抽取")
            .question("系统管理员需要回收什么权限？")
            .sourceDocument(SOURCE_CUSTOMER_DATA_ACCESS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "security", "role", "revoke", "weak-evidence"))
            .expectedEntities(List.of(
                entity("系统管理员", List.of(), List.of()),
                entity("异常权限", List.of(), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("系统管理员")
                .targetName("异常权限")
                .relationType("回收")
                .relationTypeAliases(List.of("REVOKES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("系统管理员", "回收", "异常权限"))
                .sourceName("系统管理员")
                .targetName("异常权限")
                .relationType("回收")
                .relationTypeAliases(List.of("REVOKES"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite customerDataStoresInVaultDocs(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-customer-data-stores-in-vaultdocs")
            .name("客户数据允许存放于 VaultDocs")
            .scenario("O6 平台清单型存放关系抽取")
            .question("客户数据可以存放在哪些受控平台？")
            .sourceDocument(SOURCE_CUSTOMER_DATA_ACCESS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "security", "system", "stores", "weak-evidence"))
            .expectedEntities(List.of(
                entity("客户数据", List.of(), List.of()),
                entity("VaultDocs", List.of("加密文件库"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("客户数据")
                .targetName("VaultDocs")
                .relationType("存放")
                .relationTypeAliases(List.of("STORES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("客户数据", "存放", "VaultDocs"))
                .sourceName("客户数据")
                .targetName("VaultDocs")
                .relationType("存放")
                .relationTypeAliases(List.of("STORES"))
                .build()))
            .build();
    }

    private static GraphRagEvaluationSuite customerDataStoresInDataCleanRoom(Long documentId, Long taskId) {
        return GraphRagEvaluationSuite.builder()
            .suiteId("o6-customer-data-stores-in-datacleanroom")
            .name("客户数据允许存放于 DataCleanRoom")
            .scenario("O6 平台清单型存放关系抽取")
            .question("DataCleanRoom 和客户数据是什么存放关系？")
            .sourceDocument(SOURCE_CUSTOMER_DATA_ACCESS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.82D)
            .tags(List.of("real-document", "security", "system", "stores", "weak-evidence"))
            .expectedEntities(List.of(
                entity("客户数据", List.of(), List.of()),
                entity("DataCleanRoom", List.of("受控分析环境"), List.of())
            ))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("客户数据")
                .targetName("DataCleanRoom")
                .relationType("存放")
                .relationTypeAliases(List.of("STORES"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("客户数据", "存放", "DataCleanRoom"))
                .sourceName("客户数据")
                .targetName("DataCleanRoom")
                .relationType("存放")
                .relationTypeAliases(List.of("STORES"))
                .build()))
            .forbiddenRelations(List.of(forbiddenRelation(
                "信息安全部",
                "DataCleanRoom",
                "RESPONSIBLE_FOR",
                "修订记录只能说明文档变更来源，不能直接升级成信息安全部负责 DataCleanRoom。"
            )))
            .build();
    }

    private static GraphRagEvaluationSuite.ExpectedEntity entity(String name,
                                                                List<String> aliases,
                                                                List<String> mustHaveAliases) {
        return GraphRagEvaluationSuite.ExpectedEntity.builder()
            .name(name)
            .aliases(aliases)
            .mustHaveAliases(mustHaveAliases)
            .build();
    }

    private static GraphRagEvaluationSuite.ForbiddenRelation forbiddenRelation(String sourceName,
                                                                              String targetName,
                                                                              String relationType,
                                                                              String reason) {
        return GraphRagEvaluationSuite.ForbiddenRelation.builder()
            .sourceName(sourceName)
            .targetName(targetName)
            .relationType(relationType)
            .reason(reason)
            .build();
    }
}
