package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("smartledge_knowledge_topic_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeTopicNode extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long knowledgeBaseId;

    private String topicName;

    private Long scopeId;

    private String description;

    private String aliases;

    private String examples;

    private String answerShape;

    private String executionPreference;

    private Integer sortOrder;
}
