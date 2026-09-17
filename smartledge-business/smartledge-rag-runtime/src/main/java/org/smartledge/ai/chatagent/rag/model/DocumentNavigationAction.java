package org.smartledge.ai.chatagent.rag.model;

/**
 * @description: 文档导航动作
 * @author: Song
 **/

public enum DocumentNavigationAction {
    TOPIC_CONTINUE,
    TOPIC_SWITCH,
    FRESH_TOPIC,
    SIBLING_SECTION_SWITCH,
    CHILD_SECTION_DESCEND,
    ANCESTOR_SECTION_RETURN,
    ITEM_REFERENCE,
    SECTION_ADJACENCY_LOOKUP
}
