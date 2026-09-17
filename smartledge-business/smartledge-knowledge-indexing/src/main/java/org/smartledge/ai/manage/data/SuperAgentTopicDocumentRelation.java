package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

import java.math.BigDecimal;

/**
 * @description: 数据实体
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_topic_document_relation")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentTopicDocumentRelation extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long knowledgeBaseId;

    private Long topicId;

    private Long documentId;

    private BigDecimal relationScore;

    private String relationSource;

    private String reason;
}
