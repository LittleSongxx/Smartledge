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

import java.math.BigDecimal;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_chat_retrieval_result")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatRetrievalResult extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("dialogue_code")
    private String conversationId;

    @TableField("exchange_id")
    private Long exchangeId;

    @TableField("trace_id")
    private String traceId;

    @TableField("sub_question_index")
    private Integer subQuestionIndex;

    @TableField("sub_question")
    private String subQuestion;

    @TableField("candidate_id")
    private String candidateId;

    @TableField("channel_type")
    private String channelType;

    @TableField("channel_rank")
    private Integer channelRank;

    @TableField("rrf_rank")
    private Integer rrfRank;

    @TableField("final_rank")
    private Integer finalRank;

    @TableField("original_score")
    private BigDecimal originalScore;

    @TableField("rrf_score")
    private BigDecimal rrfScore;

    @TableField("hybrid_score")
    private BigDecimal hybridScore;

    @TableField("metadata_boost")
    private BigDecimal metadataBoost;

    @TableField("vector_score")
    private BigDecimal vectorScore;

    @TableField("keyword_score")
    private BigDecimal keywordScore;

    @TableField("rerank_score")
    private BigDecimal rerankScore;

    @TableField("gate_passed")
    private Integer gatePassed;

    @TableField("is_elevated")
    private Integer isElevated;

    @TableField("is_selected")
    private Integer isSelected;

    @TableField("selection_reason")
    private String selectionReason;

    @TableField("filtered_reason")
    private String filteredReason;

    @TableField("rank_feature")
    private String rankFeature;

    @TableField("document_id")
    private Long documentId;

    @TableField("document_name")
    private String documentName;

    @TableField("chunk_id")
    private Long chunkId;

    @TableField("chunk_type")
    private String chunkType;

    @TableField("chunk_no")
    private Integer chunkNo;

    @TableField("parent_block_id")
    private Long parentBlockId;

    @TableField("parent_block_no")
    private Integer parentBlockNo;

    @TableField("section_path")
    private String sectionPath;

    @TableField("chunk_text_preview")
    private String chunkTextPreview;

    @TableField("chunk_char_count")
    private Integer chunkCharCount;

    @TableField("context_identity")
    private String contextIdentity;

    @TableField("citation_identity")
    private String citationIdentity;

    @TableField("citation_identity_hash")
    private String citationIdentityHash;

    @TableField("citation_evidence_type")
    private String citationEvidenceType;

    @TableField("context_only")
    private Integer contextOnly;

    @TableField("source_evidence_resolved")
    private Integer sourceEvidenceResolved;
}
