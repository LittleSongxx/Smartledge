package org.smartledge.ai.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;

/**
 * 文档 ACL Mapper。
 *
 * <p>查询统一走 MyBatis-Plus 的条件构造（自动带租户收窄），不做手写 SQL：
 * ACL 判定是权限边界，语句形状越简单越不容易被租户 SQL 往返改坏
 * （参见 S21-N：JSqlParser 往返会把 {@code ORDER BY ... FOR UPDATE} 重排）。</p>
 */
@Mapper
public interface SuperAgentDocumentAclMapper extends BaseMapper<SuperAgentDocumentAcl> {
}
