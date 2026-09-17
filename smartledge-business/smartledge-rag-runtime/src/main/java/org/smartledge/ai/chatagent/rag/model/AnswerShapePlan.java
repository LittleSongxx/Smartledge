package org.smartledge.ai.chatagent.rag.model;

import java.util.LinkedHashSet;
import java.util.List;

/** Immutable answer-organization policy. It does not own evidence selection or citation binding. */
public record AnswerShapePlan(List<AnswerShapeRequirement> requirements) {

    private static final AnswerShapePlan EMPTY = new AnswerShapePlan(List.of());

    public AnswerShapePlan {
        LinkedHashSet<AnswerShapeRequirement> normalized = new LinkedHashSet<>();
        if (requirements != null) {
            requirements.stream().filter(requirement -> requirement != null).forEach(normalized::add);
        }
        requirements = List.copyOf(normalized);
    }

    public static AnswerShapePlan empty() {
        return EMPTY;
    }

    public static AnswerShapePlan of(List<AnswerShapeRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return EMPTY;
        }
        AnswerShapePlan plan = new AnswerShapePlan(requirements);
        return plan.isEmpty() ? EMPTY : plan;
    }

    public boolean requires(AnswerShapeRequirement requirement) {
        return requirement != null && requirements.contains(requirement);
    }

    public boolean isEmpty() {
        return requirements.isEmpty();
    }
}
