package org.smartledge.ai.knowledge.augmentation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.model.GraphWindowNodeProjection;

import java.util.List;

@Mapper
public interface SuperAgentKgEntityMapper extends BaseMapper<SuperAgentKgEntity> {

    @Select("""
        <script>
        SELECT
            CONCAT('kg-entity-', entity_row.id) AS nodeId,
            entity_row.id AS sourceId,
            entity_row.name AS label,
            entity_row.entity_type AS entityType,
            COALESCE(degree_row.incoming_count, 0) AS incomingCount,
            COALESCE(degree_row.outgoing_count, 0) AS outgoingCount
        FROM smartledge_kg_entity entity_row
        LEFT JOIN (
            SELECT
                relation_degree.entity_id,
                SUM(relation_degree.incoming_count) AS incoming_count,
                SUM(relation_degree.outgoing_count) AS outgoing_count
            FROM (
                SELECT source_entity_id AS entity_id, 0 AS incoming_count, COUNT(*) AS outgoing_count
                FROM smartledge_kg_relation
                WHERE document_id = #{documentId} AND task_id = #{indexTaskId} AND status = 1
                GROUP BY source_entity_id
                UNION ALL
                SELECT target_entity_id AS entity_id, COUNT(*) AS incoming_count, 0 AS outgoing_count
                FROM smartledge_kg_relation
                WHERE document_id = #{documentId} AND task_id = #{indexTaskId} AND status = 1
                GROUP BY target_entity_id
            ) relation_degree
            GROUP BY relation_degree.entity_id
        ) degree_row ON degree_row.entity_id = entity_row.id
        WHERE entity_row.document_id = #{documentId}
          AND entity_row.task_id = #{indexTaskId}
          AND entity_row.status = 1
        <if test="!includeIsolates">
          AND degree_row.entity_id IS NOT NULL
        </if>
        ORDER BY
          CASE WHEN COALESCE(degree_row.incoming_count, 0) + COALESCE(degree_row.outgoing_count, 0) = 0 THEN 1 ELSE 0 END,
          COALESCE(degree_row.incoming_count, 0) + COALESCE(degree_row.outgoing_count, 0) DESC,
          entity_row.name ASC,
          entity_row.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<GraphWindowNodeProjection> selectGraphWindowNodes(
        @Param("documentId") Long documentId,
        @Param("indexTaskId") Long indexTaskId,
        @Param("includeIsolates") boolean includeIsolates,
        @Param("limit") int limit
    );

    @Select("""
        SELECT COUNT(*)
        FROM smartledge_kg_entity entity_row
        WHERE entity_row.document_id = #{documentId}
          AND entity_row.task_id = #{indexTaskId}
          AND entity_row.status = 1
          AND EXISTS (
              SELECT 1
              FROM smartledge_kg_relation relation_row
              WHERE relation_row.document_id = #{documentId}
                AND relation_row.task_id = #{indexTaskId}
                AND relation_row.status = 1
                AND (relation_row.source_entity_id = entity_row.id OR relation_row.target_entity_id = entity_row.id)
          )
        """)
    Long countConnectedGraphEntities(@Param("documentId") Long documentId,
                                     @Param("indexTaskId") Long indexTaskId);
}
