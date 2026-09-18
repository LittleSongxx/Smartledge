package org.smartledge.ai.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.model.DocumentTenantRef;

import java.util.Collection;
import java.util.List;

/**
 * @description: Mapper层
 * @author: Song
 **/

@Mapper
public interface SuperAgentDocumentMapper extends BaseMapper<SuperAgentDocument> {

    /**
     * 锁定全部启用文档，用于跨文档图谱投影的全量刷新。
     *
     * <p><b>不要写成 {@code ORDER BY id FOR UPDATE}</b>。租户拦截器会对每条语句做
     * 「解析 -> 重写 -> 重新序列化」，而运行时的 JSqlParser 4.9 在重新序列化时会把
     * {@code FOR UPDATE} 提到 {@code ORDER BY} 之前，生成 MySQL 无法解析的语句
     * （实测：{@code ... WHERE status = 1 FOR UPDATE ORDER BY id} 报 1064 语法错误），
     * 导致跨文档派生索引刷新失败、构建任务停在待修复状态。
     * 锁定语义由 {@code FOR UPDATE} 单独承担；结果只用于按 id 组装映射，不依赖返回顺序。</p>
     */
    @Select("SELECT * FROM smartledge_document WHERE status = 1 FOR UPDATE")
    List<SuperAgentDocument> selectActiveForGraphProjection();

    /**
     * 读取文档所属租户。
     *
     * <p>只取 {@code tenant_id} 一列：调用方（索引构建系统上下文）需要的是"这份数据属于哪个租户"，
     * 不是文档内容。语句里没有锁定读，形状对租户拦截器的 SQL 往返安全
     * （对比 {@link #selectActiveForGraphProjection()} 的注意事项）。</p>
     *
     * <p>租户权威不随软删消失：删除链路在同一事务先置 status=0 再解析租户写向量墓碑，
     * 语句不得按 status 过滤，否则所有文档删除都会在墓碑一步失败（见 DocumentTenantLookupSqlTest）。</p>
     */
    @Select("SELECT tenant_id FROM smartledge_document WHERE id = #{documentId}")
    Long selectTenantIdById(@Param("documentId") Long documentId);

    /** 批量读取文档租户，只返回 id 与 tenant_id 两列。 */
    @Select({"<script>",
        "SELECT id, tenant_id FROM smartledge_document WHERE status = 1 AND id IN",
        "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>#{documentId}</foreach>",
        "</script>"})
    List<DocumentTenantRef> selectTenantIdsByIds(@Param("documentIds") Collection<Long> documentIds);
}
