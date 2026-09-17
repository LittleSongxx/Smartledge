package org.smartledge.ai.auth.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明管理接口所需的权限编码（{@code resource:action}）。
 *
 * <p>可以标在方法或类上；方法注解优先。管理端拦截器对 {@code /manage/**} 采取
 * **默认拒绝**：没有声明的处理方法一律 403，因此新增接口必须先想清楚要什么权限，
 * 而不是"忘了加就默认全开"。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 权限编码，例如 {@code document:upload}。 */
    String value();
}
