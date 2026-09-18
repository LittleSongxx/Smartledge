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
 * 轮次用户反馈（质量回路的线上数据入口）。
 *
 * <p>一个用户对一轮问答只保留一条反馈，改评会覆盖原值；DOWN 反馈是金标候选的主要来源。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_chat_exchange_feedback")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatExchangeFeedback extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("dialogue_code")
    private String conversationId;

    @TableField("exchange_id")
    private Long exchangeId;

    @TableField("user_id")
    private Long userId;

    @TableField("tenant_id")
    private Long tenantId;

    /** 1:有帮助 -1:没有帮助。 */
    @TableField("rating")
    private Integer rating;

    @TableField("comment")
    private String comment;
}
