package org.smartledge.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDecision;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDisposition;
import org.smartledge.ai.chatagent.rag.model.PromptRenderedSourceEvidence;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ExplicitCitationBindingService {

    static final String TOKEN_PATTERN = "\\[([1-9][0-9]*)]";

    private static final Pattern EXPLICIT_REFERENCE_TOKEN = Pattern.compile(TOKEN_PATTERN);

    private static final String SCHEMA_VERSION = "S25_RETRIEVED_AND_EXPLICIT_CITATION_V1";

    public ExplicitCitationBindingResult bind(String answer,
                                              RagPromptAssemblyResult promptAssemblyResult,
                                              ConversationTraceRecorder traceRecorder,
                                              String executionMode) {
        ConversationTraceRecorder.StageHandle stage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.CITATION_BINDING,
                executionMode,
                "正在绑定答案中的显式引用。",
                null
            );
        try {
            BindingDomain domain = BindingDomain.from(promptAssemblyResult);
            List<Map<String, Object>> parsedTokens = new ArrayList<>();
            List<Map<String, Object>> bindings = new ArrayList<>();
            List<Map<String, Object>> rejectedTokens = new ArrayList<>();
            LinkedHashMap<String, SearchReference> finalReferences = new LinkedHashMap<>();

            Matcher matcher = EXPLICIT_REFERENCE_TOKEN.matcher(answer == null ? "" : answer);
            int occurrence = 0;
            while (matcher.find()) {
                occurrence++;
                ExplicitToken token = new ExplicitToken(matcher.group(), matcher.group(1), matcher.start(), occurrence);
                parsedTokens.add(tokenTrace(token));

                BindingResolution resolution = domain.resolve(token.referenceId());
                if (!resolution.accepted()) {
                    rejectedTokens.add(rejectionTrace(token, resolution));
                    continue;
                }

                boolean firstOccurrence = !finalReferences.containsKey(resolution.identity());
                finalReferences.putIfAbsent(resolution.identity(), resolution.reference());
                bindings.add(bindingTrace(token, resolution, firstOccurrence));
            }

            List<SearchReference> explicitCitations = List.copyOf(finalReferences.values());
            List<String> explicitCitationIdentities = List.copyOf(finalReferences.keySet());
            List<SearchReference> retrievedSources = domain.retrievedSources();
            List<String> retrievedSourceIdentities = domain.renderedSourceIdentities();
            List<String> sourceSnapshotIdentities = explicitCitationIdentities;
            Set<String> retrievedIdentitySet = Set.copyOf(retrievedSourceIdentities);
            boolean retrievedMatchesRendered = retrievedSourceIdentities.equals(
                retrievedSources.stream().map(SearchReference::uniqueKey).toList()
            );
            boolean explicitWithinRetrieved = retrievedIdentitySet.containsAll(explicitCitationIdentities);
            boolean snapshotMatchesExplicitCitations = sourceSnapshotIdentities.equals(explicitCitationIdentities);
            if (!retrievedMatchesRendered || !explicitWithinRetrieved || !snapshotMatchesExplicitCitations) {
                throw new IllegalStateException("explicit citation binding conservation failed");
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", SCHEMA_VERSION);
            snapshot.put("authority", "EXPLICIT_REFERENCE_TOKEN");
            snapshot.put("tokenSyntax", TOKEN_PATTERN);
            snapshot.put("parsedTokenCount", parsedTokens.size());
            snapshot.put("parsedTokens", parsedTokens);
            snapshot.put("bindingCount", bindings.size());
            snapshot.put("bindings", bindings);
            snapshot.put("rejectedTokenCount", rejectedTokens.size());
            snapshot.put("rejectedTokens", rejectedTokens);
            snapshot.put("renderedSourceIdentities", domain.renderedSourceIdentities());
            snapshot.put("retrievedSourceIdentities", retrievedSourceIdentities);
            snapshot.put("explicitCitationIdentities", explicitCitationIdentities);
            snapshot.put("sourceSnapshotIdentities", sourceSnapshotIdentities);
            snapshot.put("retrievedSourceReferenceCount", retrievedSources.size());
            snapshot.put("sourceSnapshotReferenceCount", explicitCitations.size());
            snapshot.put("explicitCitationsWithinRetrievedSources", explicitWithinRetrieved);
            snapshot.put("retrievedEqualsRenderedSources", retrievedMatchesRendered);
            snapshot.put("sourceSnapshotEqualsExplicitCitations", snapshotMatchesExplicitCitations);
            snapshot.put("conservationStatus", "CONSERVED");

            if (traceRecorder != null) {
                traceRecorder.completeStage(stage, "显式引用绑定完成。", snapshot);
            }
            log.info(
                "显式引用绑定完成: parsedTokenCount={}, bindingCount={}, rejectedTokenCount={}, retrievedSourceReferenceCount={}, explicitCitationCount={}",
                parsedTokens.size(),
                bindings.size(),
                rejectedTokens.size(),
                retrievedSources.size(),
                explicitCitations.size()
            );
            return new ExplicitCitationBindingResult(
                retrievedSources,
                explicitCitations,
                retrievedSourceIdentities,
                explicitCitationIdentities
            );
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(stage, "显式引用绑定失败。", exception, null);
            }
            throw exception;
        }
    }

    private static Map<String, Object> tokenTrace(ExplicitToken token) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("token", token.text());
        item.put("referenceId", token.referenceId());
        item.put("answerOffset", token.answerOffset());
        item.put("occurrence", token.occurrence());
        return item;
    }

    private static Map<String, Object> bindingTrace(ExplicitToken token,
                                                    BindingResolution resolution,
                                                    boolean firstOccurrence) {
        Map<String, Object> item = tokenTrace(token);
        item.put("identity", resolution.identity());
        item.put("manifestDispositions", resolution.manifestDispositions());
        item.put(
            "bindingDisposition",
            firstOccurrence ? "BOUND_FIRST_OCCURRENCE" : "BOUND_DUPLICATE_REFERENCE"
        );
        return item;
    }

    private static Map<String, Object> rejectionTrace(ExplicitToken token,
                                                      BindingResolution resolution) {
        Map<String, Object> item = tokenTrace(token);
        item.put("reason", resolution.rejectionReason());
        item.put("manifestDispositions", resolution.manifestDispositions());
        return item;
    }

    private record ExplicitToken(String text, String referenceId, int answerOffset, int occurrence) {
    }

    private record BindingResolution(SearchReference reference,
                                     String identity,
                                     List<String> manifestDispositions,
                                     String rejectionReason) {

        private static BindingResolution accepted(SearchReference reference,
                                                  String identity,
                                                  List<String> dispositions) {
            return new BindingResolution(reference, identity, dispositions, "");
        }

        private static BindingResolution rejected(String reason, List<String> dispositions) {
            return new BindingResolution(null, "", dispositions, reason);
        }

        private boolean accepted() {
            return rejectionReason.isEmpty();
        }
    }

    private static final class BindingDomain {

        private final Map<String, List<PromptReferenceDecision>> renderedDecisionsByReferenceId;
        private final Map<String, List<PromptReferenceDecision>> inputDecisionsByReferenceId;
        private final Map<String, SearchReference> renderedSourcesByIdentity;
        private final List<String> renderedSourceIdentities;
        private final BigInteger maxKnownReferenceId;
        private final boolean promptManifestAvailable;

        private BindingDomain(Map<String, List<PromptReferenceDecision>> renderedDecisionsByReferenceId,
                              Map<String, List<PromptReferenceDecision>> inputDecisionsByReferenceId,
                              Map<String, SearchReference> renderedSourcesByIdentity,
                              List<String> renderedSourceIdentities,
                              BigInteger maxKnownReferenceId,
                              boolean promptManifestAvailable) {
            this.renderedDecisionsByReferenceId = renderedDecisionsByReferenceId;
            this.inputDecisionsByReferenceId = inputDecisionsByReferenceId;
            this.renderedSourcesByIdentity = renderedSourcesByIdentity;
            this.renderedSourceIdentities = renderedSourceIdentities;
            this.maxKnownReferenceId = maxKnownReferenceId;
            this.promptManifestAvailable = promptManifestAvailable;
        }

        private static BindingDomain from(RagPromptAssemblyResult promptAssemblyResult) {
            if (promptAssemblyResult == null) {
                return new BindingDomain(Map.of(), Map.of(), Map.of(), List.of(), BigInteger.ZERO, false);
            }

            Map<String, List<PromptReferenceDecision>> renderedById = new LinkedHashMap<>();
            Map<String, List<PromptReferenceDecision>> inputById = new LinkedHashMap<>();
            BigInteger maxReferenceId = BigInteger.ZERO;
            for (PromptReferenceDecision decision : promptAssemblyResult.getReferenceManifest()) {
                if (!decision.referenceId().isBlank()) {
                    inputById.computeIfAbsent(decision.referenceId(), ignored -> new ArrayList<>()).add(decision);
                    maxReferenceId = max(maxReferenceId, parseReferenceId(decision.referenceId()));
                }
                if (isRenderedSource(decision) && !decision.renderedReferenceId().isBlank()) {
                    renderedById.computeIfAbsent(decision.renderedReferenceId(), ignored -> new ArrayList<>()).add(decision);
                    maxReferenceId = max(maxReferenceId, parseReferenceId(decision.renderedReferenceId()));
                }
            }

            Map<String, SearchReference> sourcesByIdentity = new LinkedHashMap<>();
            for (PromptRenderedSourceEvidence evidence : promptAssemblyResult.getRenderedSourceEvidence()) {
                sourcesByIdentity.put(evidence.identity(), evidence.reference());
            }
            return new BindingDomain(
                immutableListMap(renderedById),
                immutableListMap(inputById),
                Map.copyOf(sourcesByIdentity),
                promptAssemblyResult.getRenderedSourceIdentities(),
                maxReferenceId,
                true
            );
        }

        private BindingResolution resolve(String referenceId) {
            if (!promptManifestAvailable) {
                return BindingResolution.rejected("MISSING_PROMPT_MANIFEST", List.of());
            }
            List<PromptReferenceDecision> renderedDecisions = renderedDecisionsByReferenceId.get(referenceId);
            if (renderedDecisions != null && !renderedDecisions.isEmpty()) {
                List<String> dispositions = dispositions(renderedDecisions);
                LinkedHashSet<String> identities = renderedDecisions.stream()
                    .map(PromptReferenceDecision::identity)
                    .filter(identity -> !identity.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (identities.isEmpty()) {
                    return BindingResolution.rejected("MISSING_STABLE_IDENTITY", dispositions);
                }
                if (identities.size() > 1) {
                    return BindingResolution.rejected("AMBIGUOUS_REFERENCE_ID", dispositions);
                }
                String identity = identities.iterator().next();
                SearchReference reference = renderedSourcesByIdentity.get(identity);
                if (reference == null || !renderedSourceIdentities.contains(identity)) {
                    return BindingResolution.rejected("MISSING_RENDERED_SOURCE", dispositions);
                }
                if (!identity.equals(reference.uniqueKey())) {
                    return BindingResolution.rejected("MISSING_STABLE_IDENTITY", dispositions);
                }
                return BindingResolution.accepted(reference, identity, dispositions);
            }

            List<PromptReferenceDecision> inputDecisions = inputDecisionsByReferenceId.get(referenceId);
            if (inputDecisions != null && !inputDecisions.isEmpty()) {
                List<String> dispositions = dispositions(inputDecisions);
                if (inputDecisions.stream().anyMatch(
                    decision -> decision.disposition() == PromptReferenceDisposition.PROMPT_RENDERED_CONTEXT
                )) {
                    return BindingResolution.rejected("CONTEXT_ONLY_REFERENCE", dispositions);
                }
                return BindingResolution.rejected("NOT_RENDERED_SOURCE", dispositions);
            }

            BigInteger numericId = parseReferenceId(referenceId);
            String reason = numericId.compareTo(maxKnownReferenceId) > 0
                ? "OUT_OF_RANGE_REFERENCE_ID"
                : "UNKNOWN_REFERENCE_ID";
            return BindingResolution.rejected(reason, List.of());
        }

        private List<String> renderedSourceIdentities() {
            return renderedSourceIdentities;
        }

        private List<SearchReference> retrievedSources() {
            List<SearchReference> sources = new ArrayList<>();
            for (String identity : renderedSourceIdentities) {
                SearchReference reference = renderedSourcesByIdentity.get(identity);
                if (reference != null) {
                    sources.add(reference);
                }
            }
            return List.copyOf(sources);
        }

        private static boolean isRenderedSource(PromptReferenceDecision decision) {
            return decision.disposition() == PromptReferenceDisposition.PROMPT_RENDERED_SOURCE
                || decision.disposition() == PromptReferenceDisposition.PROMPT_REUSED_SOURCE;
        }

        private static List<String> dispositions(List<PromptReferenceDecision> decisions) {
            return decisions.stream()
                .map(decision -> decision.disposition().name())
                .distinct()
                .toList();
        }

        private static Map<String, List<PromptReferenceDecision>> immutableListMap(
            Map<String, List<PromptReferenceDecision>> source) {
            Map<String, List<PromptReferenceDecision>> snapshot = new LinkedHashMap<>();
            source.forEach((key, value) -> snapshot.put(key, List.copyOf(value)));
            return Map.copyOf(snapshot);
        }

        private static BigInteger parseReferenceId(String referenceId) {
            return new BigInteger(referenceId);
        }

        private static BigInteger max(BigInteger left, BigInteger right) {
            return left.compareTo(right) >= 0 ? left : right;
        }
    }
}
