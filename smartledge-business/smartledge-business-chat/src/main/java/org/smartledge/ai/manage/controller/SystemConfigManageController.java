package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.smartledge.ai.auth.support.AdminRequestContext;
import org.smartledge.ai.manage.config.ExecutionFailureDiagnosticProjector;
import org.smartledge.ai.manage.config.SystemConfigValidationException;
import org.smartledge.ai.manage.dto.SystemConfigHistoryDetailQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryPageQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryRestoreDto;
import org.smartledge.ai.manage.dto.SystemConfigItemUpdateDto;
import org.smartledge.ai.manage.service.SystemConfigService;
import org.smartledge.ai.manage.vo.SystemConfigCurrentVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryItemVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryPageVo;
import org.smartledge.ai.manage.vo.ExecutionFailureDiagnosticVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.smartledge.ai.auth.support.RequiresPermission;

@RestController
@RequestMapping("/manage/config")
@RequiresPermission("config:read")
public class SystemConfigManageController {

    private final SystemConfigService systemConfigService;
    private final ExecutionFailureDiagnosticProjector failureDiagnosticProjector;

    public SystemConfigManageController(SystemConfigService systemConfigService,
            ExecutionFailureDiagnosticProjector failureDiagnosticProjector) {
        this.systemConfigService = systemConfigService;
        this.failureDiagnosticProjector = failureDiagnosticProjector;
    }

    @ExceptionHandler(SystemConfigValidationException.class)
    public ApiResponse<ExecutionFailureDiagnosticVo> validationFailure(SystemConfigValidationException failure) {
        return ApiResponse.error(failure.getCode(), failure.getMessage(),
                failureDiagnosticProjector.configurationFailure(failure));
    }

    @Operation(summary = "查询当前系统配置")
    @PostMapping("/current/query")
    public ApiResponse<SystemConfigCurrentVo> current() {
        return ApiResponse.ok(systemConfigService.current());
    }

    @Operation(summary = "修改单个系统配置项")
    @PostMapping("/item/update")
    @RequiresPermission("config:write")
    public ApiResponse<SystemConfigCurrentVo> updateItem(@Valid @RequestBody SystemConfigItemUpdateDto dto,
                                                         HttpServletRequest request) {
        return ApiResponse.ok(systemConfigService.updateItem(dto, AdminRequestContext.resolveUsername(request)));
    }

    @Operation(summary = "分页查询系统配置历史")
    @PostMapping("/history/page/query")
    public ApiResponse<SystemConfigHistoryPageVo> queryHistory(
        @Valid @RequestBody(required = false) SystemConfigHistoryPageQueryDto dto) {
        return ApiResponse.ok(systemConfigService.queryHistory(dto == null ? new SystemConfigHistoryPageQueryDto() : dto));
    }

    @Operation(summary = "查询系统配置历史详情")
    @PostMapping("/history/detail/query")
    public ApiResponse<SystemConfigHistoryItemVo> queryHistoryDetail(
        @Valid @RequestBody SystemConfigHistoryDetailQueryDto dto) {
        return ApiResponse.ok(systemConfigService.queryHistoryDetail(dto));
    }

    @Operation(summary = "恢复系统配置历史版本")
    @PostMapping("/history/restore")
    @RequiresPermission("config:write")
    public ApiResponse<SystemConfigCurrentVo> restore(@Valid @RequestBody SystemConfigHistoryRestoreDto dto,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(systemConfigService.restore(dto, AdminRequestContext.resolveUsername(request)));
    }
}
