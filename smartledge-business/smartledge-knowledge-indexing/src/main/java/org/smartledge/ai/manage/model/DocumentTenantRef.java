package org.smartledge.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档 id 与所属租户的成对投影（只读查询用）。
 *
 * <p>刻意不是 {@code SuperAgentDocument} 的字段：实体声明 {@code tenantId} 会让 MyBatis-Plus 把它
 * 纳入 INSERT 的列清单，而上传路径并不设置该字段，结果是往 NOT NULL 列写 NULL。
 * 需要租户的地方用这个专用投影，实体保持原样。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTenantRef {

    private Long id;

    private Long tenantId;
}
