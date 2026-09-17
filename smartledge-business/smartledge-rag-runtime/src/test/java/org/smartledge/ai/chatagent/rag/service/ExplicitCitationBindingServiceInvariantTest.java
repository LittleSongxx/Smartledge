package org.smartledge.ai.chatagent.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDecision;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDisposition;
import org.smartledge.ai.chatagent.rag.model.PromptRenderedSourceEvidence;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S25 目标不变量：无合法 ASCII {@code [n]} 时 explicit 为空，retrieved 保持 Prompt 渲染序。
 *
 * <p>禁止再断言「无 token 则清空用户可见来源」。过渡字段 {@code sourceSnapshotIdentities}
 * 仍只等于 explicit。</p>
 */
class ExplicitCitationBindingServiceInvariantTest {

    private final ExplicitCitationBindingService binding = new ExplicitCitationBindingService();

    @Test
    @DisplayName("合法 [n] 按首次出现序进入 explicit，retrieved 保持渲染序")
    void bindsFirstOccurrenceInTokenOrder() {
        SearchReference one = source(1L, 11L);
        SearchReference two = source(2L, 22L);
        RagPromptAssemblyResult assembly = assembly(
            List.of(
                rendered(0, "1", one.uniqueKey()),
                rendered(1, "2", two.uniqueKey())
            ),
            List.of(new PromptRenderedSourceEvidence(one.uniqueKey(), one), new PromptRenderedSourceEvidence(two.uniqueKey(), two))
        );

        ExplicitCitationBindingResult result = binding.bind("结论见[2]和[1]以及再次[2]。", assembly, null, "TEST");
        assertThat(result.retrievedSourceIdentities())
            .containsExactly(one.uniqueKey(), two.uniqueKey());
        assertThat(result.explicitCitationIdentities())
            .containsExactly(two.uniqueKey(), one.uniqueKey());
        assertThat(result.sourceSnapshotIdentities()).isEqualTo(result.explicitCitationIdentities());
        assertThat(result.finalizeIdentitySnapshot().get("sourceSnapshotIdentities"))
            .isEqualTo(result.explicitCitationIdentities());
    }

    @Test
    @DisplayName("没有合法 token 时 explicit 为空，retrieved 仍等于渲染序")
    void emptyWhenNoLegalToken() {
        SearchReference one = source(1L, 11L);
        RagPromptAssemblyResult assembly = assembly(
            List.of(rendered(0, "1", one.uniqueKey())),
            List.of(new PromptRenderedSourceEvidence(one.uniqueKey(), one))
        );

        ExplicitCitationBindingResult result = binding.bind("没有引用。", assembly, null, "TEST");
        assertThat(result.explicitCitationIdentities()).isEmpty();
        assertThat(result.explicitCitations()).isEmpty();
        assertThat(result.sourceSnapshotIdentities()).isEmpty();
        assertThat(result.retrievedSourceIdentities()).containsExactly(one.uniqueKey());
        assertThat(result.retrievedSources()).extracting(SearchReference::uniqueKey)
            .containsExactly(one.uniqueKey());
    }

    @Test
    @DisplayName("Context Only 的 referenceId 不得进入 explicit，retrieved 仍保留已渲染 Source")
    void rejectsContextOnlyToken() {
        SearchReference source = source(1L, 11L);
        SearchReference context = source(2L, 22L);
        RagPromptAssemblyResult assembly = assembly(
            List.of(
                rendered(0, "1", source.uniqueKey()),
                new PromptReferenceDecision(1, 0, "2", "2", "CHUNK", "CTX:" + context.uniqueKey(),
                    PromptReferenceDisposition.PROMPT_RENDERED_CONTEXT, 8)
            ),
            List.of(new PromptRenderedSourceEvidence(source.uniqueKey(), source))
        );

        ExplicitCitationBindingResult result = binding.bind("上下文[2]不能引用。", assembly, null, "TEST");
        assertThat(result.explicitCitationIdentities()).isEmpty();
        assertThat(result.retrievedSourceIdentities()).containsExactly(source.uniqueKey());
    }

    @Test
    @DisplayName("越界 [n] 拒绝进入 explicit，retrieved 仍等于渲染序")
    void rejectsOutOfRangeToken() {
        SearchReference one = source(1L, 11L);
        RagPromptAssemblyResult assembly = assembly(
            List.of(rendered(0, "1", one.uniqueKey())),
            List.of(new PromptRenderedSourceEvidence(one.uniqueKey(), one))
        );

        ExplicitCitationBindingResult result = binding.bind("越界[99]不能引用。", assembly, null, "TEST");
        assertThat(result.explicitCitationIdentities()).isEmpty();
        assertThat(result.retrievedSourceIdentities()).containsExactly(one.uniqueKey());
    }

    @Test
    @DisplayName("全角括号不是合法 ASCII [n]，不得补进 explicit")
    void ignoresFullwidthToken() {
        SearchReference one = source(1L, 11L);
        RagPromptAssemblyResult assembly = assembly(
            List.of(rendered(0, "1", one.uniqueKey())),
            List.of(new PromptRenderedSourceEvidence(one.uniqueKey(), one))
        );

        ExplicitCitationBindingResult result = binding.bind("资料见［1］。", assembly, null, "TEST");
        assertThat(result.explicitCitationIdentities()).isEmpty();
        assertThat(result.retrievedSourceIdentities()).containsExactly(one.uniqueKey());
    }

    private PromptReferenceDecision rendered(int ordinal, String referenceId, String identity) {
        return new PromptReferenceDecision(
            ordinal, 0, referenceId, referenceId, "CHUNK", identity,
            PromptReferenceDisposition.PROMPT_RENDERED_SOURCE, 12
        );
    }

    private RagPromptAssemblyResult assembly(List<PromptReferenceDecision> manifest,
                                             List<PromptRenderedSourceEvidence> sources) {
        return new RagPromptAssemblyResult("", "", 100, 50, manifest.size(), manifest, sources, List.of());
    }

    private SearchReference source(long documentId, long chunkId) {
        SearchReference reference = new SearchReference();
        reference.setSourceType("DOCUMENT");
        reference.setTitle("doc-" + documentId);
        reference.setSnippet("snippet");
        reference.setDocumentId(documentId);
        reference.setChunkId(chunkId);
        reference.setChunkType("TEXT");
        reference.setContextOnly(false);
        reference.setSourceEvidenceResolved(true);
        return reference;
    }
}
