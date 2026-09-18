package org.smartledge.ai.chatagent.support;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 工具检索词护栏：模型可能被文档内容诱导，把指令式 payload 当搜索词发给外部服务。
 *
 * <p>策略是"清洗 + 上限 + 观测"，不阻断：清洗控制字符、限制长度，命中的注入话术
 * 只做标记交给调用方记录观测（工具轨迹与日志），语义仍按普通搜索词处理。</p>
 */
public final class ToolQuerySanitizer {

    /** 外部搜索词长度上限：远超正常检索需求，超长多半是在搬运文档内容。 */
    public static final int MAX_QUERY_CHARS = 400;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern[] SUSPICIOUS_PATTERNS = {
        Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions?|rules?|prompts?|directions?)"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above|earlier)"),
        Pattern.compile("(?i)reveal\\s+(your|the)\\s+(system\\s*)?prompt"),
        Pattern.compile("忽略(以上|之前|上述|前面|上方)(所有|全部)?(的)?(指令|规则|设定|提示|要求)"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+a|从现在开始你是|你的新(角色|设定|身份)"),
        Pattern.compile("(?i)developer\\s+message|system\\s+override")
    };

    private ToolQuerySanitizer() {
    }

    /** 清洗搜索词：去控制字符、压缩空白、限制长度。 */
    public static String sanitize(String rawQuery) {
        if (StrUtil.isBlank(rawQuery)) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(rawQuery).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.length() <= MAX_QUERY_CHARS
            ? cleaned
            : cleaned.substring(0, MAX_QUERY_CHARS) + "…";
    }

    /** 命中疑似提示注入话术：只做观测标记，不改变搜索语义。 */
    public static boolean looksInjected(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        for (Pattern pattern : SUSPICIOUS_PATTERNS) {
            if (pattern.matcher(query).find()) {
                return true;
            }
        }
        return false;
    }
}
