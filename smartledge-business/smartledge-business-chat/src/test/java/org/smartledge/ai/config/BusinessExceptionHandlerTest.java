package org.smartledge.ai.config;

import org.smartledge.common.ApiResponse;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务异常（{@code SuperAgentFrameException}）的响应形状测试。
 *
 * <p>问题来源（S23 开工实测）：`smartledge-common-frame` 的 `DefaultExceptionHandler` 位于
 * `org.smartledge.exception`，落在本应用的组件扫描根 `org.smartledge.ai` **之外**，因此在这个应用里
 * 从未生效 —— 业务校验失败（口令太短、登录名重复、角色不属于本租户……）全部退化成
 * `HTTP 500 + {"status":500,"error":"Internal Server Error"}`，用户看不到任何可行动的原因。
 * 实测既有端点也一样（`/manage/knowledge/base/detail` 传不存在的 id 就是 500）。</p>
 *
 * <p>本测试只锁定**映射**这一件事（standalone 装配，不经 Spring 上下文）：
 * 4xx + 平台统一的 {@code ApiResponse} 信封，与 `AuthFailureExceptionHandler` 对认证授权异常的
 * 处置一致，前端因此只需要一套错误处理。advice 在真实应用里是否被扫描到，由真实请求验收证明。</p>
 */
class BusinessExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new BusinessExceptionProbeController())
        .setControllerAdvice(new BusinessExceptionHandler())
        .build();

    @Test
    @DisplayName("业务校验失败返回 400 + 可读原因，而不是 500")
    void mapsBusinessExceptionToReadableClientError() throws Exception {
        mockMvc.perform(post("/manage/probe/business-error"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(10054))
            .andExpect(jsonPath("$.message").value("该登录名已被使用。"));
    }

    /** 探针：只保留"会抛业务异常"这一种形状。 */
    @RestController
    static class BusinessExceptionProbeController {

        @PostMapping("/manage/probe/business-error")
        public ApiResponse<String> businessError() {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "该登录名已被使用。");
        }
    }
}
