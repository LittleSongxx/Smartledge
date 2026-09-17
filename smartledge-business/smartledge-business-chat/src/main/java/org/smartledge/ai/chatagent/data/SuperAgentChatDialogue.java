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
@TableName("smartledge_chat_dialogue")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatDialogue extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("dialogue_code")
    private String conversationId;

    /** 归属用户 id；0 表示无归属（B3 之前的历史会话），按不可访问处理。 */
    private Long userId;

    @TableField("dialogue_stage")
    private Integer sessionStatus;

    @TableField("chat_mode")
    private Integer chatMode;

    @TableField("selected_document_id")
    private Long selectedDocumentId;

    @TableField("selected_document_name")
    private String selectedDocumentName;

    @TableField("knowledge_base_selection_mode")
    private String knowledgeBaseSelectionMode;

    @TableField("selected_knowledge_base_ids_json")
    private String selectedKnowledgeBaseIdsJson;

    @TableField("selected_knowledge_base_names_json")
    private String selectedKnowledgeBaseNamesJson;
}
