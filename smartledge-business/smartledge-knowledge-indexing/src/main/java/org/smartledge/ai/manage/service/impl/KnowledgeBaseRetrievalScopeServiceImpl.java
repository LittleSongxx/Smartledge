package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.KnowledgeBaseRuntimeConfigResolver;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseRuntimeConfigSource;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentKnowledgeService;
import org.smartledge.ai.manage.service.DocumentMetadataStore;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.service.KnowledgeBaseRetrievalScopeService;
import org.smartledge.ai.manage.support.DocumentMetadataFilter;
import org.smartledge.ai.manage.support.DocumentMetadataFilterParser;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeBaseRetrievalScopeServiceImpl implements KnowledgeBaseRetrievalScopeService {

    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final KnowledgeBaseRuntimeConfigResolver runtimeConfigResolver;
    private final DocumentMetadataStore documentMetadataStore;
    private final DocumentMetadataFilterParser metadataFilterParser;
    private final DocumentAclStore documentAclStore;

    public KnowledgeBaseRetrievalScopeServiceImpl(KnowledgeBaseManageService knowledgeBaseManageService,
                                                  DocumentKnowledgeService documentKnowledgeService,
                                                  KnowledgeBaseRuntimeConfigResolver runtimeConfigResolver,
                                                  DocumentMetadataStore documentMetadataStore,
                                                  DocumentAclStore documentAclStore) {
        this.knowledgeBaseManageService = knowledgeBaseManageService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.documentMetadataStore = documentMetadataStore;
        this.metadataFilterParser = new DocumentMetadataFilterParser();
        this.documentAclStore = documentAclStore;
    }

    @Override
    public KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                                  KnowledgeBaseSelectionMode selectionMode,
                                                  Collection<String> selectedKnowledgeBaseIds) {
        KnowledgeBaseSelectionMode resolvedMode = selectionMode == null ? KnowledgeBaseSelectionMode.NONE : selectionMode;
        if (chatMode == ChatQueryMode.OPEN_CHAT || resolvedMode == KnowledgeBaseSelectionMode.NONE) {
            return KnowledgeBaseSelectionSnapshot.none(runtimeConfigResolver.resolve(List.of()));
        }
        List<SuperAgentKnowledgeBase> selectedBases = switch (resolvedMode) {
            case ALL -> selectAllWithRetrievableDocuments();
            case SELECTED -> selectExplicit(selectedKnowledgeBaseIds);
            case NONE -> List.of();
        };
        if (selectedBases.isEmpty()) {
            return KnowledgeBaseSelectionSnapshot.builder()
                .selectionMode(resolvedMode)
                .ragRuntimeOptions(runtimeConfigResolver.resolve(List.of()))
                .build();
        }
        List<Long> selectedBaseIds = selectedBases.stream().map(SuperAgentKnowledgeBase::getId).toList();
        List<KnowledgeDocumentDescriptor> readyDocuments = documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(selectedBaseIds);
        if (readyDocuments == null) {
            readyDocuments = List.of();
        }
        Map<Long, org.smartledge.ai.manage.model.DocumentMetadata> metadataByDocumentId = loadDocumentMetadata(readyDocuments);
        List<KnowledgeDocumentDescriptor> allowedDocuments = filterByDocumentMetadata(selectedBases, readyDocuments, metadataByDocumentId);
        // 元数据过滤决定"知识库配置允许的范围"，ACL 决定"这个身份在其中的可见范围"。
        // 两步都是收窄，先后顺序不影响结果：这里第二次收窄只做交集，不放宽。
        allowedDocuments = filterByDocumentAcl(allowedDocuments);
        RagRuntimeOptions options = runtimeConfigResolver.resolve(selectedBases.stream()
            .map(base -> new KnowledgeBaseRuntimeConfigSource(
                base.getId(),
                base.getBaseName(),
                base.getRetrievalConfigJson(),
                base.getGraphRagConfigJson(),
                base.getRaptorConfigJson()))
            .toList());
        return KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(resolvedMode)
            .selectedKnowledgeBaseIds(selectedBaseIds)
            .selectedKnowledgeBaseNames(selectedBases.stream().map(SuperAgentKnowledgeBase::getBaseName).toList())
            .allowedDocuments(allowedDocuments)
            .allowedDocumentIds(allowedDocuments.stream()
                .map(KnowledgeDocumentDescriptor::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
            .allowedTaskIds(allowedDocuments.stream()
                .map(KnowledgeDocumentDescriptor::getLastIndexTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
            .ragRuntimeOptions(options)
            .build();
    }

    private List<KnowledgeDocumentDescriptor> filterByDocumentMetadata(List<SuperAgentKnowledgeBase> selectedBases,
                                                                       List<KnowledgeDocumentDescriptor> readyDocuments,
                                                                       Map<Long, org.smartledge.ai.manage.model.DocumentMetadata> metadataByDocumentId) {
        if (readyDocuments == null || readyDocuments.isEmpty()) {
            return List.of();
        }
        Map<Long, DocumentMetadataFilter> filtersByKnowledgeBase = new LinkedHashMap<>();
        for (SuperAgentKnowledgeBase base : selectedBases) {
            try {
                filtersByKnowledgeBase.put(base.getId(), metadataFilterParser.parse(base.getMetadataFilterJson()));
            }
            catch (RuntimeException exception) {
                // A malformed filter is a closed scope for this knowledge base, never a fallback to all documents.
                filtersByKnowledgeBase.put(base.getId(), null);
            }
        }
        return readyDocuments.stream()
            .filter(document -> document != null && document.getKnowledgeBaseId() != null)
            .filter(document -> {
                DocumentMetadataFilter filter = filtersByKnowledgeBase.get(document.getKnowledgeBaseId());
                return filter != null && (filter.conditionCount() == 0
                    || filter.matches(metadataByDocumentId.get(document.getDocumentId())));
            })
            .toList();
    }

    /**
     * 按当前身份的文档级 ACL 收窄可见文档集合（fail closed）。
     *
     * <p>没有认证主体就没有可见性依据：返回空集合而不是"全部放行"。ACL 读取失败同样返回空集合
     * （收窄由 {@link DocumentAclStore} 的实现保证）。</p>
     *
     * <p>下游（路由、五通道、RAPTOR 汇总、跨文档社区、最终证据与引用）只消费本方法的输出，
     * 因此可见性只在这里求值一次。</p>
     */
    private List<KnowledgeDocumentDescriptor> filterByDocumentAcl(List<KnowledgeDocumentDescriptor> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        RequestIdentity identity = TenantContext.getIdentity();
        if (identity == null) {
            // 未认证请求没有租户与主体：可见集合为空，而不是沿用"无 ACL 即放行"。
            return List.of();
        }
        Set<Long> visibleDocumentIds;
        try {
            visibleDocumentIds = documentAclStore.visibleDocumentIds(
                documents.stream().map(KnowledgeDocumentDescriptor::getDocumentId).toList(),
                identity);
        }
        catch (RuntimeException exception) {
            // 与元数据读取同一口径：可见性输入不可用时收窄为空集合。
            // 实现本身也保证 fail closed，这里是边界上的第二道防线，避免某个实现漏掉契约。
            log.warn("文档可见性解析失败，本次按不可见处理，tenantId={}, documentCount={}",
                identity.tenantId(), documents.size(), exception);
            return List.of();
        }
        return documents.stream()
            .filter(document -> visibleDocumentIds.contains(document.getDocumentId()))
            .toList();
    }

    private Map<Long, org.smartledge.ai.manage.model.DocumentMetadata> loadDocumentMetadata(
        List<KnowledgeDocumentDescriptor> readyDocuments) {
        if (documentMetadataStore == null) {
            return Map.of();
        }
        try {
            Map<Long, org.smartledge.ai.manage.model.DocumentMetadata> metadata = documentMetadataStore
                .loadByDocumentIds(readyDocuments.stream()
                    .map(KnowledgeDocumentDescriptor::getDocumentId)
                    .filter(Objects::nonNull)
                    .toList());
            return metadata == null ? Map.of() : metadata;
        }
        catch (RuntimeException exception) {
            // Metadata is a hard scope input.  An unavailable reader cannot widen a non-empty filter.
            return Map.of();
        }
    }

    private List<SuperAgentKnowledgeBase> selectAllWithRetrievableDocuments() {
        List<SuperAgentKnowledgeBase> enabledBases = knowledgeBaseManageService.listAllEnabled();
        if (enabledBases.isEmpty()) {
            return List.of();
        }
        List<Long> baseIds = enabledBases.stream().map(SuperAgentKnowledgeBase::getId).toList();
        LinkedHashSet<Long> nonEmptyBaseIds = documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(baseIds)
            .stream()
            .map(KnowledgeDocumentDescriptor::getKnowledgeBaseId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return enabledBases.stream()
            .filter(base -> nonEmptyBaseIds.contains(base.getId()))
            .toList();
    }

    private List<SuperAgentKnowledgeBase> selectExplicit(Collection<String> selectedKnowledgeBaseIds) {
        List<Long> ids = selectedKnowledgeBaseIds == null
            ? List.of()
            : selectedKnowledgeBaseIds.stream()
                .map(this::parseKnowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollUtil.isEmpty(ids)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请选择至少一个知识库。");
        }
        List<SuperAgentKnowledgeBase> bases = knowledgeBaseManageService.listEnabledByIds(ids);
        Map<Long, SuperAgentKnowledgeBase> byId = bases.stream()
            .collect(Collectors.toMap(SuperAgentKnowledgeBase::getId, Function.identity()));
        for (Long id : ids) {
            if (!byId.containsKey(id)) {
                // 面向用户：不暴露内部知识库 ID，只指出下一步（B1-3 / S21-J）。内部 ID 进日志。
                log.warn("请求的知识库不存在或已停用，knowledgeBaseId={}", id);
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "所选知识库不存在或已停用，请刷新页面后重新选择。");
            }
        }
        return ids.stream().map(byId::get).toList();
    }

    private Long parseKnowledgeBaseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            Long id = Long.valueOf(rawId.trim());
            if (id <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return id;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "knowledgeBaseId 格式非法。");
        }
    }
}
