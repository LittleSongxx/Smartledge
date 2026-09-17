package org.smartledge.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_chat_exchange")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatExchange extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("dialogue_code")
    private String conversationId;

    @TableField("user_prompt")
    private String question;

    @TableField("reply_content")
    private String answer;

    @TableField("reasoning_note_list")
    private String thinkingSteps;

    @TableField("source_snapshot_list")
    private String referenceList;

    @TableField("followup_suggestion_list")
    private String recommendationList;

    @TableField("tool_trace_list")
    private String usedToolList;

    @TableField("debug_trace_json")
    private String debugTraceJson;

    @TableField("exchange_state")
    private Integer turnStatus;

    @TableField("finish_note")
    private String errorMessage;

    @TableField("first_token_latency_ms")
    private Long firstResponseTimeMs;

    @TableField("total_latency_ms")
    private Long totalResponseTimeMs;

    @TableField("knowledge_base_selection_mode")
    private String knowledgeBaseSelectionMode;

    @TableField("selected_knowledge_base_ids_json")
    private String selectedKnowledgeBaseIdsJson;

    @TableField("selected_knowledge_base_names_json")
    private String selectedKnowledgeBaseNamesJson;

    @TableField("retrieval_config_snapshot_json")
    private String retrievalConfigSnapshotJson;
}
