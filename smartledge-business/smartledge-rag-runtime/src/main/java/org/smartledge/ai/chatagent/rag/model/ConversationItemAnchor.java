package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 会话编号项锚点
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationItemAnchor {

    private Integer itemIndex;

    private String itemText;

    private Long structureNodeId;

    private String canonicalPath;

    public boolean isEmpty() {
        return itemIndex == null
            && (itemText == null || itemText.isBlank())
            && structureNodeId == null
            && (canonicalPath == null || canonicalPath.isBlank());
    }
}
