package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判断索引构建任务是否处于**可修复**状态（S21-O）。
 *
 * <p>背景：GraphRAG 已提交图谱、但派生组件（typed index / 跨文档投影 / 观测投影）失败时，
 * 构建链路按设计把任务留在 {@code RUNNING} 且写入 {@code REPAIR_REQUIRED}，等一个"修复触发点"
 * 从已提交的检查点继续。但实际上没有触发点：文档会停在构建中，既不可检索也不可重建，
 * 只能等对账任务的 2 小时失联判定。</p>
 *
 * <p>本类只做一件纯判定：给定的 {@code ext_json} 里是否记录了 {@code REPAIR_REQUIRED}。
 * 判定依据是构建链路自己写入的检查点（{@code graphRagBuild.status} /
 * {@code graphRagBuild.outerTaskDisposition}），不引入第二套状态语义。</p>
 */
public final class RepairableBuildTaskPolicy {

    private static final String ROOT_KEY = "graphRagBuild";

    private static final String REPAIR_REQUIRED = "REPAIR_REQUIRED";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RepairableBuildTaskPolicy() {
    }

    /**
     * {@code extJson} 是否记录了"需要修复"的 GraphRAG 结果。
     *
     * <p>解析失败按"不可修复"处理：判定不了就交给原来的阻塞路径，不猜测。</p>
     */
    public static boolean isRepairRequired(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(extJson);
            JsonNode graphRagState = root == null ? null : root.get(ROOT_KEY);
            if (graphRagState == null || !graphRagState.isObject()) {
                return false;
            }
            return REPAIR_REQUIRED.equalsIgnoreCase(text(graphRagState.get("outerTaskDisposition")))
                || REPAIR_REQUIRED.equalsIgnoreCase(text(graphRagState.get("status")));
        }
        catch (Exception exception) {
            return false;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }
}
