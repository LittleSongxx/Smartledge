package org.smartledge.ai.knowledge.indexing.model;

import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;

import java.util.Objects;

/**
 * Immutable identity of one indexing build and the source revision it is
 * allowed to consume.
 *
 * <p>The cohort is frozen at task creation/dispatch time.  Downstream stages
 * must use these values instead of mutable pointers on the document row.</p>
 */
public record IndexingCohort(Long documentId,
                             Long indexTaskId,
                             Long sourceParseTaskId,
                             Long planId,
                             String strategySnapshot) {

    public IndexingCohort {
        requireId(documentId, "documentId");
        requireId(indexTaskId, "indexTaskId");
        requireId(sourceParseTaskId, "sourceParseTaskId");
        requireId(planId, "planId");
        if (strategySnapshot == null) {
            strategySnapshot = "";
        }
    }

    /**
     * Freezes and validates the lineage represented by the persisted rows.
     * No database access is performed here; callers provide the rows already
     * read for the same operation so the contract is deterministic and easy to
     * exercise in isolation.
     */
    public static IndexingCohort freeze(SuperAgentDocument document,
                                        SuperAgentDocumentTask indexTask,
                                        SuperAgentDocumentTask sourceParseTask,
                                        SuperAgentDocumentStrategyPlan plan) {
        if (document == null || !isActive(document.getStatus()) || document.getId() == null) {
            throw new IllegalArgumentException("document is missing or inactive");
        }
        if (indexTask == null || !isActive(indexTask.getStatus())
            || !Objects.equals(indexTask.getDocumentId(), document.getId())
            || !Objects.equals(indexTask.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())) {
            throw new IllegalArgumentException("index task does not belong to document");
        }
        if (sourceParseTask == null || !isActive(sourceParseTask.getStatus())
            || !Objects.equals(sourceParseTask.getDocumentId(), document.getId())
            || !Objects.equals(sourceParseTask.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            || !Objects.equals(sourceParseTask.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())) {
            throw new IllegalArgumentException("source parse task must be successful and belong to document");
        }
        if (!Objects.equals(indexTask.getSourceParseTaskId(), sourceParseTask.getId())) {
            throw new IllegalArgumentException("index task sourceParseTaskId does not match source parse task");
        }
        if (plan == null || !isActive(plan.getStatus())
            || !Objects.equals(plan.getId(), indexTask.getPlanId())
            || !Objects.equals(plan.getDocumentId(), document.getId())) {
            throw new IllegalArgumentException("plan does not belong to document or index task");
        }
        if (!Objects.equals(plan.getStrategySnapshot(), indexTask.getStrategySnapshot())) {
            throw new IllegalArgumentException("plan strategy snapshot drift");
        }
        return new IndexingCohort(
            document.getId(),
            indexTask.getId(),
            sourceParseTask.getId(),
            plan.getId(),
            plan.getStrategySnapshot()
        );
    }

    private static boolean isActive(Integer status) {
        return Objects.equals(status, BusinessStatus.YES.getCode());
    }

    private static void requireId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
