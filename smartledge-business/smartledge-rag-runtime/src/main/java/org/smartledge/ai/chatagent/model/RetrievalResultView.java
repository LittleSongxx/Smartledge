package org.smartledge.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResultView {

    private Long id;
    private String traceId;
    private int subQuestionIndex;
    private String subQuestion;
    private String candidateId;
    private String channelType;
    private Integer channelRank;
    private Integer rrfRank;
    private Integer finalRank;
    private BigDecimal originalScore;
    private BigDecimal rrfScore;
    private BigDecimal hybridScore;
    private BigDecimal metadataBoost;
    private BigDecimal vectorScore;
    private BigDecimal keywordScore;
    private BigDecimal rerankScore;
    private boolean gatePassed;
    private boolean isElevated;
    private boolean isSelected;
    private String selectionReason;
    private String filteredReason;
    private String rankFeature;
    private Long documentId;
    private String documentName;
    private Long chunkId;
    private String chunkType;
    private Integer chunkNo;
    private Long parentBlockId;
    private Integer parentBlockNo;
    private String sectionPath;
    private String chunkTextPreview;
    private Integer chunkCharCount;
    private String contextIdentity;
    private String citationIdentity;
    private String citationIdentityHash;
    private String citationEvidenceType;
    private boolean contextOnly;
    private boolean sourceEvidenceResolved;
    private Instant createTime;
}
