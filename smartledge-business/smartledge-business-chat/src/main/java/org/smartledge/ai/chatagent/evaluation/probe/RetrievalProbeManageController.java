package org.smartledge.ai.chatagent.evaluation.probe;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.auth.support.AdminRequestContext;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeException;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeProjection;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeQuery;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeResult;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeRequestGuard;
import org.smartledge.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import org.smartledge.ai.auth.support.RequiresPermission;

@Slf4j
@RestController
@RequestMapping("/manage/evaluation/retrieval/probe")
@RequiresPermission("observe:read")
public class RetrievalProbeManageController {

    private final RetrievalProbeProjection projection;
    private final RetrievalProbeRequestGuard requestGuard;

    public RetrievalProbeManageController(RetrievalProbeProjection projection,
                                          RetrievalProbeRequestGuard requestGuard) {
        this.projection = projection;
        this.requestGuard = requestGuard;
    }

    @PostMapping
    public ApiResponse<RetrievalProbeResult> probe(@RequestBody RetrievalProbeQuery query,
                                                   HttpServletRequest request) {
        String administrator = AdminRequestContext.resolveUsername(request);
        requestGuard.requireEnabled();
        requestGuard.acquire(administrator);
        validateRequestShape(query);
        RetrievalProbeResult result = projection.probe(query);
        log.info("Retrieval probe completed: administrator={}, experimentId={}", administrator,
            query == null ? "" : query.getExperimentId());
        return ApiResponse.ok(result);
    }

    private void validateRequestShape(RetrievalProbeQuery query) {
        if (query == null) {
            throw new RetrievalProbeException("INVALID_REQUEST", "retrieval probe request is required");
        }
        if (query.getUnknownProperties() != null && !query.getUnknownProperties().isEmpty()) {
            if (query.getUnknownProperties().keySet().stream().anyMatch(this::isBuildTimeParameter)) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "build parameters require a new index cohort");
            }
            throw new RetrievalProbeException("INVALID_REQUEST", "unknown retrieval probe request field");
        }
        if (query.getOverrides() != null) {
            if (query.getOverrides().getBuildParameters() != null && !query.getOverrides().getBuildParameters().isEmpty()) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "build parameters require a new index cohort");
            }
            if (query.getOverrides().getUnknownProperties() != null && !query.getOverrides().getUnknownProperties().isEmpty()) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "unknown retrieval probe override field");
            }
        }
    }

    private boolean isBuildTimeParameter(String name) {
        String normalized = name == null ? "" : name.replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("parser") || normalized.contains("chunk") || normalized.contains("embedding")
            || normalized.contains("graphrag") || normalized.contains("raptor");
    }

    @ExceptionHandler(RetrievalProbeException.class)
    public ResponseEntity<ApiResponse<String>> probeException(RetrievalProbeException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = switch (exception.getReasonCode()) {
            case "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "ENDPOINT_DISABLED" -> HttpStatus.NOT_FOUND;
            case "INVALID_SCOPE", "INVALID_QUERY", "INVALID_EXPERIMENT_ID", "INVALID_OVERRIDE", "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.warn("Retrieval probe rejected: administrator={}, reasonCode={}",
            AdminRequestContext.resolveUsername(request), exception.getReasonCode());
        return ResponseEntity.status(status)
            .body(ApiResponse.error(status.value(), exception.getReasonCode()));
    }
}
