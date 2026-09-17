package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @description: 服务层
 * @author: Song
 **/
public interface DocumentProfileService {

    SuperAgentDocumentProfile generateProfile(Long documentId,
                                              DocumentAnalysisResult analysisResult,
                                              List<SuperAgentDocumentStructureNode> structureNodes);

    SuperAgentDocumentProfile regenerateProfile(Long documentId);

    List<SuperAgentDocumentProfile> batchRegenerateProfiles(Collection<Long> documentIds);

    Optional<SuperAgentDocumentProfile> getByDocumentId(Long documentId);
}
