package org.smartledge.ai.chatagent.evaluation;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.auth.support.AdminRequestContext;
import org.smartledge.ai.chatagent.evaluation.EvaluationExchangeSnapshot;
import org.smartledge.ai.chatagent.evaluation.EvaluationExchangeSnapshotProjection;
import org.smartledge.ai.chatagent.evaluation.EvaluationExchangeSnapshotQuery;
import org.smartledge.ai.chatagent.evaluation.EvaluationSnapshotQueryException;
import org.smartledge.ai.chatagent.evaluation.EvaluationSnapshotRequestGuard;
import org.smartledge.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.smartledge.ai.auth.support.RequiresPermission;

@Slf4j
@RestController
@RequestMapping("/manage/evaluation/exchange/snapshot")
@RequiresPermission("observe:read")
public class EvaluationSnapshotManageController {

    private final EvaluationExchangeSnapshotProjection projection;
    private final EvaluationSnapshotRequestGuard requestGuard;

    public EvaluationSnapshotManageController(EvaluationExchangeSnapshotProjection projection,
                                              EvaluationSnapshotRequestGuard requestGuard) {
        this.projection = projection;
        this.requestGuard = requestGuard;
    }

    @Operation(summary = "按 exchange ID 查询同轮评测快照")
    @PostMapping("/query")
    public ApiResponse<List<EvaluationExchangeSnapshot>> query(
        @RequestBody EvaluationExchangeSnapshotQuery query,
        HttpServletRequest request) {
        String administrator = AdminRequestContext.resolveUsername(request);
        requestGuard.requireEnabled();
        requestGuard.acquire(administrator);
        int exchangeCount = query == null || query.exchangeIds() == null ? 0 : query.exchangeIds().size();
        List<EvaluationExchangeSnapshot> snapshots = projection.query(query);
        log.info(
            "Evaluation snapshot export completed: administrator={}, requestedExchangeCount={}, exportedSnapshotCount={}",
            administrator,
            exchangeCount,
            snapshots.size()
        );
        return ApiResponse.ok(snapshots);
    }

    @ExceptionHandler(EvaluationSnapshotQueryException.class)
    public ResponseEntity<ApiResponse<String>> snapshotQueryException(
        EvaluationSnapshotQueryException exception,
        HttpServletRequest request) {
        HttpStatus status = statusFor(exception.getReasonCode());
        log.warn(
            "Evaluation snapshot export rejected: administrator={}, reasonCode={}",
            AdminRequestContext.resolveUsername(request),
            exception.getReasonCode()
        );
        return ResponseEntity.status(status)
            .body(ApiResponse.error(status.value(), exception.getReasonCode()));
    }

    private HttpStatus statusFor(String reasonCode) {
        if ("RATE_LIMIT_EXCEEDED".equals(reasonCode)) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if ("ENDPOINT_DISABLED".equals(reasonCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("EXCHANGE_NOT_FOUND".equals(reasonCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("EXCHANGE_NOT_TERMINAL".equals(reasonCode)) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
