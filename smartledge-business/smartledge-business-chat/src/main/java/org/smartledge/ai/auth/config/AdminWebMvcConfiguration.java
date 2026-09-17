package org.smartledge.ai.auth.config;

import org.smartledge.ai.auth.support.ManagePermissionInterceptor;
import org.smartledge.ai.auth.support.PreviewModeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理接口权限声明与预览模式的 MVC 配置。
 *
 * <p>登录与 token 用途判定已由 security filter chain 承担，这里只保留"按方法注解判定细粒度权限"。
 * {@code /manage/**} 不再有任何排除路径：曾经被排除的评测快照导出接口现在同样需要管理端身份
 * 与 {@code observe:read} 权限。</p>
 */
@Configuration
public class AdminWebMvcConfiguration implements WebMvcConfigurer {

    private final ManagePermissionInterceptor managePermissionInterceptor;

    private final PreviewModeInterceptor previewModeInterceptor;

    public AdminWebMvcConfiguration(ManagePermissionInterceptor managePermissionInterceptor,
                                    PreviewModeInterceptor previewModeInterceptor) {
        this.managePermissionInterceptor = managePermissionInterceptor;
        this.previewModeInterceptor = previewModeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(managePermissionInterceptor)
            .addPathPatterns("/manage/**");

        registry.addInterceptor(previewModeInterceptor)
            .addPathPatterns("/**");
    }
}
