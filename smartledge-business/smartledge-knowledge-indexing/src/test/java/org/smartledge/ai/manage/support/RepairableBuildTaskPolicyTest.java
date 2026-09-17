package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可修复构建任务判定（S21-O）的不变量测试。
 *
 * <p>背景：GraphRAG 已提交图谱但派生组件失败时，构建链路会把任务留在 RUNNING 并写入
 * {@code REPAIR_REQUIRED}，等待一个并不存在的修复触发点；文档因此停在构建中，既不可检索也不可重建，
 * 只能等对账任务 2 小时后判失联。判定"这个任务是否可以由新的构建请求取代"必须只依赖
 * 构建链路自己写入的检查点，且解析不了时不得猜测。</p>
 */
class RepairableBuildTaskPolicyTest {

    @Test
    @DisplayName("检查点记录 REPAIR_REQUIRED 时判定为可修复")
    void repairRequiredDispositionIsRepairable() {
        String extJson = """
            {"graphRagBuild":{"status":"REPAIR_REQUIRED","stage":"OUTCOME","attempt":1,"maxAttempts":2,
             "outerTaskDisposition":"REPAIR_REQUIRED","kgCommitted":true,
             "typedIndexOutcome":"SUCCESS","crossDocumentIndexOutcome":"FAILED"}}
            """;
        assertThat(RepairableBuildTaskPolicy.isRepairRequired(extJson)).isTrue();
    }

    @Test
    @DisplayName("只有 status 记录 REPAIR_REQUIRED 时同样可修复")
    void repairRequiredStatusIsRepairable() {
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("{\"graphRagBuild\":{\"status\":\"REPAIR_REQUIRED\"}}"))
            .isTrue();
    }

    @Test
    @DisplayName("其它 disposition 与无检查点的任务不得被判为可修复")
    void otherStatesAreNotRepairable() {
        assertThat(RepairableBuildTaskPolicy.isRepairRequired(
            "{\"graphRagBuild\":{\"status\":\"KG_COMMITTED\",\"outerTaskDisposition\":\"CONTINUE\"}}")).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired(
            "{\"graphRagBuild\":{\"outerTaskDisposition\":\"FAIL_INDEX_TASK\"}}")).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("{\"otherKey\":1}")).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("{}")).isFalse();
    }

    @Test
    @DisplayName("缺失或损坏的 ext_json 一律按不可修复处理（不猜测）")
    void malformedPayloadIsNotRepairable() {
        assertThat(RepairableBuildTaskPolicy.isRepairRequired(null)).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("")).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("not-json")).isFalse();
        assertThat(RepairableBuildTaskPolicy.isRepairRequired("{\"graphRagBuild\":\"REPAIR_REQUIRED\"}")).isFalse();
    }
}
