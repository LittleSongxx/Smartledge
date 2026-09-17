package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;

import java.util.List;
import java.util.Optional;

public interface DocumentTableQueryPlanAdvisor {

    Optional<DocumentTableQueryPlanAdvice> advise(String question, List<DocumentTableDescriptor> tables);
}
