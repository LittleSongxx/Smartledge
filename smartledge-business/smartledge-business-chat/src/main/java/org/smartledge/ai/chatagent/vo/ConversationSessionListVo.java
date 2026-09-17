package org.smartledge.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.ConversationSessionView;

import java.util.List;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionListVo {

    private long pageNo;

    private long pageSize;

    private long totalSize;

    private long totalPages;

    private List<ConversationSessionView> sessions;
}
