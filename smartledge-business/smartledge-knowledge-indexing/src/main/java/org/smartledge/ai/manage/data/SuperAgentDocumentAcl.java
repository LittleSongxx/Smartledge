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
 * 文档访问控制（smartledge_document_acl）。
 *
 * <p>ACL 在**查询时**解析，不写进 chunk / 向量 / 索引元数据：改权限立即生效，不需要重建索引。
 * 任何"索引期固化权限"的做法都会造成权限漂移，这也是本阶段明确否证的方案之一。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document_acl")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentAcl extends BaseTableData {

    /** 主体类型：用户。 */
    public static final String PRINCIPAL_TYPE_USER = "USER";

    /** 主体类型：角色。 */
    public static final String PRINCIPAL_TYPE_ROLE = "ROLE";

    /** 权限等级：可读（足以出现在检索范围内）。 */
    public static final String PERMISSION_READ = "READ";

    /** 权限等级：可写（策略确认、索引构建）。 */
    public static final String PERMISSION_WRITE = "WRITE";

    /** 权限等级：可管理（删除、授权）。 */
    public static final String PERMISSION_MANAGE = "MANAGE";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long documentId;

    private String principalType;

    private Long principalId;

    private String permission;

    private Long grantedBy;
}
