package org.smartledge.ai.prompt;

import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.compiler.STLexer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description: Prompt 模板渲染组件
 * @author: Song
 **/
@Component
public class PromptTemplateService {

    private static final String PROMPT_DIR = "prompt/";
    private static final String TEMPLATE_SUFFIX = ".st";

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String render(String templateName, Map<String, ?> variables) {
        String templatePath = normalizeTemplatePath(templateName);
        String template = templateCache.computeIfAbsent(templatePath, this::loadTemplate);
        STGroup group = new STGroup('<', '>');
        ST compiled = new ST(group, template);
        Map<String, Object> normalized = normalizeVariables(variables);
        // Current production templates use scalar variables and if/else, validated from ST's lexer.
        java.util.Set<String> missing = new java.util.TreeSet<>();
        var tokens = compiled.impl.tokens;
        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            if (token.getType() == STLexer.ID && !normalized.containsKey(token.getText())) {
                missing.add(token.getText());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Not all variables were replaced in the template. Missing variable names are: " + missing + ".");
        }
        normalized.forEach(compiled::add);
        return compiled.render().trim();
    }

    private Map<String, Object> normalizeVariables(Map<String, ?> variables) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) {
            return normalized;
        }
        variables.forEach((key, value) -> normalized.put(key, value == null ? "" : value));
        return normalized;
    }

    private String normalizeTemplatePath(String templateName) {
        String normalized = templateName == null ? "" : templateName.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith(PROMPT_DIR)) {
            normalized = PROMPT_DIR + normalized;
        }
        if (!normalized.endsWith(TEMPLATE_SUFFIX)) {
            normalized = normalized + TEMPLATE_SUFFIX;
        }
        return normalized;
    }

    private String loadTemplate(String templatePath) {
        Resource resource = resourceLoader.getResource("classpath:" + templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Prompt 模板不存在: classpath:" + templatePath);
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
        catch (Exception exception) {
            throw new IllegalStateException("读取 Prompt 模板失败: classpath:" + templatePath, exception);
        }
    }
}
