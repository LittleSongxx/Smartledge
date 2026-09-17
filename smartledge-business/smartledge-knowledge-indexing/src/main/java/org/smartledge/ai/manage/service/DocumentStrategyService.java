package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.support.ChunkCandidate;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;
import org.smartledge.ai.manage.support.DocumentStrategyPlanDraft;
import org.smartledge.ai.manage.support.ParentBlockCandidate;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentStrategyService {

    DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult);

    List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                        List<SuperAgentDocumentStrategyStep> baseSteps,
                                                        List<Integer> requestParentStrategyTypes,
                                                        List<Integer> requestChildStrategyTypes,
                                                        Long documentId);

    List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                 SuperAgentDocumentStrategyPlan plan,
                                                 List<SuperAgentDocumentStrategyStep> steps,
                                                 List<SuperAgentDocumentBlock> documentBlocks);

    /**
     * A1：用新的 keywords/questions 覆盖切块后重算加权正文（{@code [KEYWORDS]/[QUESTIONS]} 段），
     * 复用与切块期一致的拼接逻辑，保证 contentWithWeight 只有一处组装规则。构建期回填 auto keyword/question 时调用。
     *
     * @param candidate     待回填切块（keywords/questions 已被覆盖为新值）
     * @param keywordsJson  新 keywords（JSON 数组字符串，可空）
     * @param questionsJson 新 questions（JSON 数组字符串，可空）
     * @return 重算后的 contentWithWeight
     */
    String rebuildContentWithWeight(ChunkCandidate candidate, String keywordsJson, String questionsJson);
}
