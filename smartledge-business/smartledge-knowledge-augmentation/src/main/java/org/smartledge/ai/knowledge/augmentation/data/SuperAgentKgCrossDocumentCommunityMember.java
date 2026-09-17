package org.smartledge.ai.knowledge.augmentation.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_kg_cross_document_community_member")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgCrossDocumentCommunityMember extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String scopeKey;

    private Long communityId;

    private String communityKey;

    private Long relationGroupId;

    private String relationGroupKey;

    private String sourceGroupKey;

    private String targetGroupKey;

    private String relationType;

    private Integer relationCount;

    private Integer evidenceCount;

    private Integer documentCount;

    private String metadataJson;
}
