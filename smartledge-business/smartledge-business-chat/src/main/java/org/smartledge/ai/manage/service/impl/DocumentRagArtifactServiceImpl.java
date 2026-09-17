package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentParentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.data.SuperAgentDocumentTable;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableCell;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableColumn;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableRow;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCommunity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.manage.dto.DocumentRagArtifactNodeDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactNodePageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactGraphWindowQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactRelationPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactTableWindowQueryDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCommunityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.knowledge.augmentation.model.GraphWindowNodeProjection;
import org.smartledge.ai.manage.model.artifact.DocumentRagArtifactType;
import org.smartledge.ai.manage.service.DocumentRagArtifactService;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodeDetailVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodePageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactGraphWindowVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactRelationPageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactTableWindowVo;
import org.smartledge.ai.manage.vo.DocumentRagSnapshotVo;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.smartledge.enums.DocumentVectorStatusEnum;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
public class DocumentRagArtifactServiceImpl implements DocumentRagArtifactService {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static final int DEFAULT_NODE_PAGE_SIZE = 12;

    private static final int DEFAULT_RELATION_PAGE_SIZE = 5;

    private static final int DEFAULT_TABLE_ROW_PAGE_SIZE = 30;

    private static final int DEFAULT_TABLE_COLUMN_LIMIT = 20;

    private static final int DEFAULT_GRAPH_NODE_LIMIT = 300;

    private static final int DEFAULT_GRAPH_EDGE_LIMIT = 500;

    private static final int MAX_GRAPH_NODE_LIMIT = 300;

    private static final int MAX_GRAPH_EDGE_LIMIT = 500;

    private static final int PREVIEW_LENGTH = 180;

    private static final int DETAIL_RELATED_LIMIT = 6;

    private static final int DETAIL_KEYWORD_LIMIT = 12;

    private static final int DETAIL_QUESTION_LIMIT = 8;

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentBlockMapper blockMapper;

    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final SuperAgentDocumentTableMapper tableMapper;

    private final SuperAgentDocumentTableRowMapper tableRowMapper;

    private final SuperAgentDocumentTableColumnMapper tableColumnMapper;

    private final SuperAgentDocumentTableCellMapper tableCellMapper;

    private final SuperAgentKgEntityMapper kgEntityMapper;

    private final SuperAgentKgRelationMapper kgRelationMapper;

    private final SuperAgentKgCommunityMapper kgCommunityMapper;

    private final SuperAgentKgEvidenceMapper kgEvidenceMapper;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final ObjectMapper objectMapper;

    @Override
    public DocumentRagArtifactNodePageVo queryNodePage(DocumentRagArtifactNodePageQueryDto dto) {
        ArtifactContext context = resolveContext(dto.getDocumentId(), dto.getParseTaskId(), dto.getIndexTaskId());
        DocumentRagArtifactType nodeType = requireNodeType(dto.getNodeType());
        int pageNo = positiveOrDefault(dto.getPageNo(), 1);
        int pageSize = positiveOrDefault(dto.getPageSize(), DEFAULT_NODE_PAGE_SIZE);
        String keyword = StrUtil.trim(dto.getKeyword());

        NodePage result = switch (nodeType) {
            case DOCUMENT -> queryDocumentPage(context, keyword, pageNo, pageSize);
            case STRUCTURE_NODE -> queryStructurePage(context, dto, keyword, pageNo, pageSize);
            case PARSE_BLOCK -> queryBlockPage(context, keyword, pageNo, pageSize);
            case PARENT_BLOCK -> queryParentPage(context, keyword, pageNo, pageSize);
            case TABLE -> queryTablePage(context, keyword, pageNo, pageSize);
            case CHILD_CHUNK -> queryChunkPage(context, keyword, pageNo, pageSize);
            case KG_ENTITY -> queryEntityPage(context, dto, keyword, pageNo, pageSize);
            case KG_COMMUNITY -> queryCommunityPage(context, keyword, pageNo, pageSize);
            case KG_EVIDENCE -> queryEvidencePage(context, dto, keyword, pageNo, pageSize);
            case RAPTOR_NODE -> queryRaptorPage(context, dto, keyword, pageNo, pageSize);
        };

        return new DocumentRagArtifactNodePageVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            nodeType.getCode(),
            pageNo,
            pageSize,
            result.total(),
            result.records()
        );
    }

    @Override
    public DocumentRagArtifactNodeDetailVo queryNodeDetail(DocumentRagArtifactNodeDetailQueryDto dto) {
        ArtifactContext context = resolveContext(dto.getDocumentId(), dto.getParseTaskId(), dto.getIndexTaskId());
        NodeIdentity identity = requireNodeIdentity(dto.getNodeId());

        return switch (identity.type()) {
            case DOCUMENT -> documentDetail(context, identity.sourceId());
            case STRUCTURE_NODE -> structureDetail(context, identity.sourceId());
            case PARSE_BLOCK -> blockDetail(context, identity.sourceId());
            case PARENT_BLOCK -> parentDetail(context, identity.sourceId());
            case TABLE -> tableDetail(context, identity.sourceId());
            case CHILD_CHUNK -> chunkDetail(context, identity.sourceId());
            case KG_ENTITY -> entityDetail(context, identity.sourceId());
            case KG_COMMUNITY -> communityDetail(context, identity.sourceId());
            case KG_EVIDENCE -> evidenceDetail(context, identity.sourceId());
            case RAPTOR_NODE -> raptorDetail(context, identity.sourceId());
        };
    }

    @Override
    public DocumentRagArtifactGraphWindowVo queryGraphWindow(DocumentRagArtifactGraphWindowQueryDto dto) {
        int maxNodes = positiveOrDefault(dto.getMaxNodes(), DEFAULT_GRAPH_NODE_LIMIT);
        int maxEdges = positiveOrDefault(dto.getMaxEdges(), DEFAULT_GRAPH_EDGE_LIMIT);
        if (maxNodes > MAX_GRAPH_NODE_LIMIT || maxEdges > MAX_GRAPH_EDGE_LIMIT) {
            throw invalidRequest("图谱窗口最多返回 300 个节点和 500 条关系。");
        }

        ArtifactContext context = resolveContext(dto.getDocumentId(), dto.getParseTaskId(), dto.getIndexTaskId());
        boolean includeIsolates = !Boolean.FALSE.equals(dto.getIncludeIsolates());
        long totalEntities = kgEntityMapper.selectCount(graphEntityScopeWrapper(context));
        long connectedEntities = Objects.requireNonNullElse(
            kgEntityMapper.countConnectedGraphEntities(context.document().getId(), context.indexTaskId()),
            0L
        );
        long totalRelations = kgRelationMapper.selectCount(graphWindowRelationScopeWrapper(context));

        List<GraphWindowNodeProjection> queriedNodes = kgEntityMapper.selectGraphWindowNodes(
            context.document().getId(),
            context.indexTaskId(),
            includeIsolates,
            maxNodes + 1
        );
        boolean nodeLimitReached = queriedNodes.size() > maxNodes;
        List<DocumentRagArtifactGraphWindowVo.NodeItem> nodes = queriedNodes.stream()
            .limit(maxNodes)
            .map(this::graphWindowNode)
            .toList();
        List<Long> returnedEntityIds = nodes.stream()
            .map(DocumentRagArtifactGraphWindowVo.NodeItem::getSourceId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        List<SuperAgentKgRelation> queriedRelations = returnedEntityIds.isEmpty()
            ? List.of()
            : kgRelationMapper.selectList(
                graphWindowRelationScopeWrapper(context)
                    .select(
                        SuperAgentKgRelation::getId,
                        SuperAgentKgRelation::getSourceEntityId,
                        SuperAgentKgRelation::getTargetEntityId,
                        SuperAgentKgRelation::getRelationType,
                        SuperAgentKgRelation::getDescription,
                        SuperAgentKgRelation::getWeight
                    )
                    .in(SuperAgentKgRelation::getSourceEntityId, returnedEntityIds)
                    .in(SuperAgentKgRelation::getTargetEntityId, returnedEntityIds)
                    .orderByDesc(SuperAgentKgRelation::getWeight)
                    .orderByAsc(SuperAgentKgRelation::getId)
                    .last("limit " + (maxEdges + 1))
            );
        boolean edgeLimitReached = queriedRelations.size() > maxEdges;
        List<DocumentRagArtifactGraphWindowVo.EdgeItem> edges = queriedRelations.stream()
            .limit(maxEdges)
            .map(this::graphWindowEdge)
            .toList();
        boolean truncated = nodeLimitReached
            || edgeLimitReached
            || totalEntities > nodes.size()
            || totalRelations > edges.size();

        DocumentRagArtifactGraphWindowVo.Stats stats = new DocumentRagArtifactGraphWindowVo.Stats(
            totalEntities,
            connectedEntities,
            Math.max(0L, totalEntities - connectedEntities),
            totalRelations,
            nodes.size(),
            edges.size(),
            truncated
        );
        return new DocumentRagArtifactGraphWindowVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            stats,
            nodes,
            edges
        );
    }

    private LambdaQueryWrapper<SuperAgentKgEntity> graphEntityScopeWrapper(ArtifactContext context) {
        return new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEntity::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
    }

    private DocumentRagArtifactGraphWindowVo.NodeItem graphWindowNode(GraphWindowNodeProjection projection) {
        return new DocumentRagArtifactGraphWindowVo.NodeItem(
            projection.getNodeId(),
            projection.getSourceId(),
            projection.getLabel(),
            projection.getEntityType(),
            projection.getIncomingCount(),
            projection.getOutgoingCount()
        );
    }

    private LambdaQueryWrapper<SuperAgentKgRelation> graphWindowRelationScopeWrapper(ArtifactContext context) {
        return new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, context.document().getId())
            .eq(SuperAgentKgRelation::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
    }

    private DocumentRagArtifactGraphWindowVo.EdgeItem graphWindowEdge(SuperAgentKgRelation relation) {
        return new DocumentRagArtifactGraphWindowVo.EdgeItem(
            "kg-relation-" + relation.getId(),
            relation.getId(),
            DocumentRagArtifactType.KG_ENTITY.nodeId(relation.getSourceEntityId()),
            DocumentRagArtifactType.KG_ENTITY.nodeId(relation.getTargetEntityId()),
            StrUtil.blankToDefault(relation.getRelationType(), "关联"),
            StrUtil.blankToDefault(relation.getDescription(), ""),
            relation.getWeight()
        );
    }

    @Override
    public DocumentRagArtifactRelationPageVo queryRelationPage(DocumentRagArtifactRelationPageQueryDto dto) {
        ArtifactContext context = resolveContext(dto.getDocumentId(), dto.getParseTaskId(), dto.getIndexTaskId());
        NodeIdentity identity = requireNodeIdentity(dto.getNodeId());
        RelationDirection direction = requireDirection(dto.getDirection());
        int pageNo = positiveOrDefault(dto.getPageNo(), 1);
        int pageSize = positiveOrDefault(dto.getPageSize(), DEFAULT_RELATION_PAGE_SIZE);
        if (identity.type() == DocumentRagArtifactType.KG_ENTITY) {
            return queryGraphRelationPage(context, identity, direction, dto.getRelationTypes(), pageNo, pageSize);
        }
        if (direction == RelationDirection.INCOMING || direction == RelationDirection.OUTGOING) {
            throw invalidRequest("INCOMING/OUTGOING 方向仅支持 KG_ENTITY。");
        }
        ArtifactFocus focus = loadRelationFocus(context, identity);
        List<RelationSegment> segments = relationSegments(context, focus, direction);
        long total = segments.stream().mapToLong(RelationSegment::total).sum();
        List<DocumentRagArtifactRelationPageVo.RelationItem> records = readRelationPage(segments, pageNo, pageSize);

        return new DocumentRagArtifactRelationPageVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            identity.type().nodeId(identity.sourceId()),
            direction.name(),
            pageNo,
            pageSize,
            total,
            records
        );
    }

    @Override
    public DocumentRagArtifactTableWindowVo queryTableWindow(DocumentRagArtifactTableWindowQueryDto dto) {
        ArtifactContext context = resolveContext(dto.getDocumentId(), dto.getParseTaskId(), dto.getIndexTaskId());
        NodeIdentity identity = requireNodeIdentity(dto.getTableNodeId());
        if (identity.type() != DocumentRagArtifactType.TABLE) {
            throw invalidRequest("tableNodeId 必须是 TABLE 节点。");
        }
        SuperAgentDocumentTable table = tableMapper.selectOne(
            tableIdentityWrapper(context, identity.sourceId()).last("limit 1")
        );
        if (table == null) {
            throw nodeNotFound();
        }

        int pageNo = positiveOrDefault(dto.getPageNo(), 1);
        int pageSize = positiveOrDefault(dto.getPageSize(), DEFAULT_TABLE_ROW_PAGE_SIZE);
        int columnOffset = dto.getColumnOffset() == null ? 0 : Math.max(0, dto.getColumnOffset());
        int columnLimit = positiveOrDefault(dto.getColumnLimit(), DEFAULT_TABLE_COLUMN_LIMIT);
        if (pageSize > 50 || columnLimit > 50 || pageSize * columnLimit > 2500) {
            throw invalidRequest("表格窗口不能超过 2500 个单元格。");
        }

        Page<SuperAgentDocumentTableRow> rowPage = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentTableRow> rows = tableRowMapper.selectPage(
            rowPage,
            tableRowWrapper(context, table.getId())
                .select(
                    SuperAgentDocumentTableRow::getId,
                    SuperAgentDocumentTableRow::getRowNo,
                    SuperAgentDocumentTableRow::getRowText
                )
                .orderByAsc(SuperAgentDocumentTableRow::getRowNo, SuperAgentDocumentTableRow::getId)
        );
        long totalColumns = tableColumnMapper.selectCount(tableColumnWrapper(context, table.getId()));
        List<SuperAgentDocumentTableColumn> columns = tableColumnMapper.selectList(
            tableColumnWrapper(context, table.getId())
                .select(
                    SuperAgentDocumentTableColumn::getId,
                    SuperAgentDocumentTableColumn::getColumnNo,
                    SuperAgentDocumentTableColumn::getColumnName,
                    SuperAgentDocumentTableColumn::getValueType
                )
                .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo, SuperAgentDocumentTableColumn::getId)
                .last(limitClause(columnOffset, columnLimit))
        );

        List<Long> rowIds = rows.getRecords().stream().map(SuperAgentDocumentTableRow::getId).toList();
        List<Long> columnIds = columns.stream().map(SuperAgentDocumentTableColumn::getId).toList();
        Map<Long, List<SuperAgentDocumentTableCell>> cellsByRow = new LinkedHashMap<>();
        if (!rowIds.isEmpty() && !columnIds.isEmpty()) {
            tableCellMapper.selectList(tableCellWrapper(context, table.getId(), rowIds, columnIds)
                    .select(
                        SuperAgentDocumentTableCell::getId,
                        SuperAgentDocumentTableCell::getRowId,
                        SuperAgentDocumentTableCell::getColumnId,
                        SuperAgentDocumentTableCell::getRowNo,
                        SuperAgentDocumentTableCell::getColumnNo,
                        SuperAgentDocumentTableCell::getCellText,
                        SuperAgentDocumentTableCell::getNumericValue,
                        SuperAgentDocumentTableCell::getSourceCellRef,
                        SuperAgentDocumentTableCell::getBboxJson
                    )
                    .orderByAsc(SuperAgentDocumentTableCell::getRowNo, SuperAgentDocumentTableCell::getColumnNo,
                        SuperAgentDocumentTableCell::getId))
                .forEach(cell -> cellsByRow.computeIfAbsent(cell.getRowId(), ignored -> new ArrayList<>()).add(cell));
        }

        List<DocumentRagArtifactTableWindowVo.ColumnItem> columnItems = columns.stream()
            .map(column -> new DocumentRagArtifactTableWindowVo.ColumnItem(
                column.getId(), column.getColumnNo(), column.getColumnName(), column.getValueType()
            ))
            .toList();
        List<DocumentRagArtifactTableWindowVo.RowItem> rowItems = rows.getRecords().stream()
            .map(row -> new DocumentRagArtifactTableWindowVo.RowItem(
                row.getId(),
                row.getRowNo(),
                row.getRowText(),
                cellsByRow.getOrDefault(row.getId(), List.of()).stream()
                    .map(cell -> new DocumentRagArtifactTableWindowVo.CellItem(
                        cell.getId(),
                        cell.getRowId(),
                        cell.getColumnId(),
                        cell.getRowNo(),
                        cell.getColumnNo(),
                        cell.getCellText(),
                        cell.getNumericValue(),
                        cell.getSourceCellRef(),
                        cell.getBboxJson()
                    ))
                    .toList()
            ))
            .toList();
        return new DocumentRagArtifactTableWindowVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            identity.type().nodeId(identity.sourceId()),
            rows.getTotal(),
            totalColumns,
            pageNo,
            pageSize,
            columnOffset,
            columnLimit,
            columnItems,
            rowItems
        );
    }

    private DocumentRagArtifactRelationPageVo queryGraphRelationPage(ArtifactContext context,
                                                                     NodeIdentity identity,
                                                                     RelationDirection direction,
                                                                     List<String> requestedRelationTypes,
                                                                     int pageNo,
                                                                     int pageSize) {
        if (direction != RelationDirection.INCOMING && direction != RelationDirection.OUTGOING) {
            throw invalidRequest("KG_ENTITY 关系方向仅支持 INCOMING 或 OUTGOING。");
        }
        SuperAgentKgEntity focus = kgEntityMapper.selectOne(
            entityIdentityWrapper(context, identity.sourceId()).last("limit 1")
        );
        if (focus == null) {
            throw nodeNotFound();
        }

        List<String> relationTypes = normalizeRelationTypes(requestedRelationTypes);
        Page<SuperAgentKgRelation> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentKgRelation> relationPage = kgRelationMapper.selectPage(
            page,
            graphRelationWrapper(context, identity.sourceId(), direction, relationTypes)
        );
        List<Long> neighborIds = relationPage.getRecords().stream()
            .map(relation -> direction == RelationDirection.INCOMING
                ? relation.getSourceEntityId()
                : relation.getTargetEntityId())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, SuperAgentKgEntity> neighbors = new LinkedHashMap<>();
        if (!neighborIds.isEmpty()) {
            kgEntityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
                    .select(
                        SuperAgentKgEntity::getId,
                        SuperAgentKgEntity::getName,
                        SuperAgentKgEntity::getNormalizedName,
                        SuperAgentKgEntity::getEntityType,
                        SuperAgentKgEntity::getDescription
                    )
                    .eq(SuperAgentKgEntity::getDocumentId, context.document().getId())
                    .eq(SuperAgentKgEntity::getTaskId, context.indexTaskId())
                    .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())
                    .in(SuperAgentKgEntity::getId, neighborIds))
                .forEach(entity -> neighbors.put(entity.getId(), entity));
        }

        List<DocumentRagArtifactRelationPageVo.RelationItem> records = relationPage.getRecords().stream()
            .map(relation -> graphRelationItem(direction, relation, neighbors))
            .filter(Objects::nonNull)
            .toList();
        return new DocumentRagArtifactRelationPageVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            identity.type().nodeId(identity.sourceId()),
            direction.name(),
            pageNo,
            pageSize,
            relationPage.getTotal(),
            records
        );
    }

    private LambdaQueryWrapper<SuperAgentKgRelation> graphRelationWrapper(ArtifactContext context,
                                                                           Long entityId,
                                                                           RelationDirection direction,
                                                                           List<String> relationTypes) {
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .select(
                SuperAgentKgRelation::getId,
                SuperAgentKgRelation::getSourceEntityId,
                SuperAgentKgRelation::getTargetEntityId,
                SuperAgentKgRelation::getRelationType,
                SuperAgentKgRelation::getDescription,
                SuperAgentKgRelation::getWeight
            )
            .eq(SuperAgentKgRelation::getDocumentId, context.document().getId())
            .eq(SuperAgentKgRelation::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
        if (direction == RelationDirection.INCOMING) {
            wrapper.eq(SuperAgentKgRelation::getTargetEntityId, entityId);
        } else {
            wrapper.eq(SuperAgentKgRelation::getSourceEntityId, entityId);
        }
        if (!relationTypes.isEmpty()) {
            wrapper.in(SuperAgentKgRelation::getRelationType, relationTypes);
        }
        return wrapper.orderByDesc(SuperAgentKgRelation::getWeight)
            .orderByAsc(SuperAgentKgRelation::getId);
    }

    private DocumentRagArtifactRelationPageVo.RelationItem graphRelationItem(
        RelationDirection direction,
        SuperAgentKgRelation relation,
        Map<Long, SuperAgentKgEntity> neighbors) {
        Long neighborId = direction == RelationDirection.INCOMING
            ? relation.getSourceEntityId()
            : relation.getTargetEntityId();
        SuperAgentKgEntity neighbor = neighbors.get(neighborId);
        if (neighbor == null) {
            return null;
        }
        String sourceNodeId = DocumentRagArtifactType.KG_ENTITY.nodeId(relation.getSourceEntityId());
        String targetNodeId = DocumentRagArtifactType.KG_ENTITY.nodeId(relation.getTargetEntityId());
        String detail = firstNotBlank(relation.getDescription(), "权重 " + valueOrDash(relation.getWeight()));
        DocumentRagSnapshotVo.ArtifactGraphEdgeItem edge = new DocumentRagSnapshotVo.ArtifactGraphEdgeItem(
            "kg-relation-" + relation.getId(),
            sourceNodeId,
            targetNodeId,
            "kg-relation",
            StrUtil.blankToDefault(relation.getRelationType(), "关联"),
            detail
        );
        return new DocumentRagArtifactRelationPageVo.RelationItem(edge, entityNode(neighbor));
    }

    private List<String> normalizeRelationTypes(List<String> relationTypes) {
        if (relationTypes == null || relationTypes.isEmpty()) {
            return List.of();
        }
        List<String> normalized = relationTypes.stream()
            .map(StrUtil::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        if (normalized.size() > 20 || normalized.stream().anyMatch(value -> value.length() > 64)) {
            throw invalidRequest("关系类型过滤不合法。");
        }
        return normalized;
    }

    private NodePage queryDocumentPage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        DocumentRagSnapshotVo.ArtifactGraphNodeItem node = documentNode(context.document());
        if (pageNo > 1 || !matchesDocument(context.document(), keyword)) {
            return new NodePage(matchesDocument(context.document(), keyword) ? 1L : 0L, List.of());
        }
        return new NodePage(1L, List.of(node));
    }

    private NodePage queryStructurePage(ArtifactContext context,
                                        DocumentRagArtifactNodePageQueryDto dto,
                                        String keyword,
                                        int pageNo,
                                        int pageSize) {
        Page<SuperAgentDocumentStructureNode> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentStructureNode> result = structureNodeMapper.selectPage(
            page,
            structurePageWrapper(context, dto, keyword)
        );
        return nodePage(result, this::structureNode);
    }

    private LambdaQueryWrapper<SuperAgentDocumentStructureNode> structurePageWrapper(
        ArtifactContext context,
        DocumentRagArtifactNodePageQueryDto dto,
        String keyword) {
        if (Boolean.TRUE.equals(dto.getRootOnly()) && dto.getParentNodeId() != null) {
            throw invalidRequest("rootOnly 与 parentNodeId 不能同时使用。");
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> wrapper =
            new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
                .select(
                    SuperAgentDocumentStructureNode::getId,
                    SuperAgentDocumentStructureNode::getNodeNo,
                    SuperAgentDocumentStructureNode::getNodeType,
                    SuperAgentDocumentStructureNode::getParentNodeId,
                    SuperAgentDocumentStructureNode::getDepth,
                    SuperAgentDocumentStructureNode::getNodeCode,
                    SuperAgentDocumentStructureNode::getTitle,
                    SuperAgentDocumentStructureNode::getCanonicalPath,
                    SuperAgentDocumentStructureNode::getSectionPath,
                    SuperAgentDocumentStructureNode::getSyntaxNodeType
                )
                .eq(SuperAgentDocumentStructureNode::getDocumentId, context.document().getId())
                .eq(SuperAgentDocumentStructureNode::getParseTaskId, context.parseTaskId())
                .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode());
        if (Boolean.TRUE.equals(dto.getRootOnly())) {
            wrapper.isNull(SuperAgentDocumentStructureNode::getParentNodeId);
        } else if (dto.getParentNodeId() != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getParentNodeId, dto.getParentNodeId());
        }
        if (dto.getLevel() != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getDepth, dto.getLevel());
        }
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentDocumentStructureNode::getTitle, keyword)
                    .or().like(SuperAgentDocumentStructureNode::getNodeCode, keyword)
                    .or().like(SuperAgentDocumentStructureNode::getSectionPath, keyword)
                    .or().like(SuperAgentDocumentStructureNode::getCanonicalPath, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentDocumentStructureNode::getId, number)
                        .or().eq(SuperAgentDocumentStructureNode::getNodeNo, number);
                }
            });
        }
        return wrapper.orderByAsc(
            SuperAgentDocumentStructureNode::getDepth,
            SuperAgentDocumentStructureNode::getNodeNo,
            SuperAgentDocumentStructureNode::getId
        );
    }

    private NodePage queryBlockPage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        if (context.parseTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentDocumentBlock> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentBlock> result = blockMapper.selectPage(page, blockPageWrapper(context, keyword));
        return nodePage(result, this::blockNode);
    }

    private LambdaQueryWrapper<SuperAgentDocumentBlock> blockPageWrapper(ArtifactContext context, String keyword) {
        LambdaQueryWrapper<SuperAgentDocumentBlock> wrapper = new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .select(
                SuperAgentDocumentBlock::getId,
                SuperAgentDocumentBlock::getBlockNo,
                SuperAgentDocumentBlock::getBlockType,
                SuperAgentDocumentBlock::getSectionPath,
                SuperAgentDocumentBlock::getPageNo,
                SuperAgentDocumentBlock::getPageRange,
                SuperAgentDocumentBlock::getBboxJson
            )
            .eq(SuperAgentDocumentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentBlock::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode());
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentDocumentBlock::getBlockType, keyword)
                    .or().like(SuperAgentDocumentBlock::getSectionPath, keyword)
                    .or().like(SuperAgentDocumentBlock::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentDocumentBlock::getId, number)
                        .or().eq(SuperAgentDocumentBlock::getBlockNo, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentDocumentBlock::getBlockNo, SuperAgentDocumentBlock::getId);
    }

    private NodePage queryParentPage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        if (context.indexTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentDocumentParentBlock> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentParentBlock> result = parentBlockMapper.selectPage(page, parentPageWrapper(context, keyword));
        return nodePage(result, this::parentNode);
    }

    private LambdaQueryWrapper<SuperAgentDocumentParentBlock> parentPageWrapper(ArtifactContext context, String keyword) {
        LambdaQueryWrapper<SuperAgentDocumentParentBlock> wrapper = new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .select(
                SuperAgentDocumentParentBlock::getId,
                SuperAgentDocumentParentBlock::getParentNo,
                SuperAgentDocumentParentBlock::getSectionPath,
                SuperAgentDocumentParentBlock::getChildCount,
                SuperAgentDocumentParentBlock::getStartChunkNo,
                SuperAgentDocumentParentBlock::getEndChunkNo,
                SuperAgentDocumentParentBlock::getPageRange
            )
            .eq(SuperAgentDocumentParentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentParentBlock::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode());
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentDocumentParentBlock::getSectionPath, keyword)
                    .or().like(SuperAgentDocumentParentBlock::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentDocumentParentBlock::getId, number)
                        .or().eq(SuperAgentDocumentParentBlock::getParentNo, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentDocumentParentBlock::getParentNo, SuperAgentDocumentParentBlock::getId);
    }

    private NodePage queryChunkPage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        if (context.indexTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentDocumentChunk> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentChunk> result = chunkMapper.selectPage(page, chunkPageWrapper(context, keyword));
        return nodePage(result, this::chunkNode);
    }

    private LambdaQueryWrapper<SuperAgentDocumentChunk> chunkPageWrapper(ArtifactContext context, String keyword) {
        LambdaQueryWrapper<SuperAgentDocumentChunk> wrapper = new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .select(
                SuperAgentDocumentChunk::getId,
                SuperAgentDocumentChunk::getParentBlockId,
                SuperAgentDocumentChunk::getChunkNo,
                SuperAgentDocumentChunk::getSectionPath,
                SuperAgentDocumentChunk::getChunkType,
                SuperAgentDocumentChunk::getTitle,
                SuperAgentDocumentChunk::getVectorStatus,
                SuperAgentDocumentChunk::getPageNo,
                SuperAgentDocumentChunk::getPageRange
            )
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode());
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentDocumentChunk::getTitle, keyword)
                    .or().like(SuperAgentDocumentChunk::getChunkType, keyword)
                    .or().like(SuperAgentDocumentChunk::getSectionPath, keyword)
                    .or().like(SuperAgentDocumentChunk::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentDocumentChunk::getId, number)
                        .or().eq(SuperAgentDocumentChunk::getChunkNo, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId);
    }

    private NodePage queryTablePage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        if (context.parseTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentDocumentTable> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentTable> result = tableMapper.selectPage(page, tablePageWrapper(context, keyword));
        return nodePage(result, this::tableNode);
    }

    private LambdaQueryWrapper<SuperAgentDocumentTable> tablePageWrapper(ArtifactContext context, String keyword) {
        LambdaQueryWrapper<SuperAgentDocumentTable> wrapper = new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .select(
                SuperAgentDocumentTable::getId,
                SuperAgentDocumentTable::getBlockId,
                SuperAgentDocumentTable::getTableNo,
                SuperAgentDocumentTable::getSectionPath,
                SuperAgentDocumentTable::getPageNo,
                SuperAgentDocumentTable::getPageRange,
                SuperAgentDocumentTable::getTitle,
                SuperAgentDocumentTable::getRowCount,
                SuperAgentDocumentTable::getColumnCount,
                SuperAgentDocumentTable::getBboxJson
            )
            .eq(SuperAgentDocumentTable::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTable::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode());
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentDocumentTable::getTitle, keyword)
                    .or().like(SuperAgentDocumentTable::getSectionPath, keyword)
                    .or().like(SuperAgentDocumentTable::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentDocumentTable::getId, number)
                        .or().eq(SuperAgentDocumentTable::getTableNo, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId);
    }

    private NodePage queryEntityPage(ArtifactContext context,
                                     DocumentRagArtifactNodePageQueryDto dto,
                                     String keyword,
                                     int pageNo,
                                     int pageSize) {
        Page<SuperAgentKgEntity> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentKgEntity> result = kgEntityMapper.selectPage(page, entityPageWrapper(context, dto, keyword));
        return nodePage(result, this::entityNode);
    }

    private LambdaQueryWrapper<SuperAgentKgEntity> entityPageWrapper(ArtifactContext context,
                                                                      DocumentRagArtifactNodePageQueryDto dto,
                                                                      String keyword) {
        LambdaQueryWrapper<SuperAgentKgEntity> wrapper = new LambdaQueryWrapper<SuperAgentKgEntity>()
            .select(
                SuperAgentKgEntity::getId,
                SuperAgentKgEntity::getName,
                SuperAgentKgEntity::getNormalizedName,
                SuperAgentKgEntity::getEntityType,
                SuperAgentKgEntity::getDescription
            )
            .eq(SuperAgentKgEntity::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEntity::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
        if (StrUtil.isNotBlank(dto.getEntityType())) {
            wrapper.eq(SuperAgentKgEntity::getEntityType, StrUtil.trim(dto.getEntityType()));
        }
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentKgEntity::getName, keyword)
                    .or().like(SuperAgentKgEntity::getNormalizedName, keyword)
                    .or().like(SuperAgentKgEntity::getEntityType, keyword)
                    .or().like(SuperAgentKgEntity::getDescription, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentKgEntity::getId, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentKgEntity::getName, SuperAgentKgEntity::getId);
    }

    private NodePage queryCommunityPage(ArtifactContext context, String keyword, int pageNo, int pageSize) {
        Page<SuperAgentKgCommunity> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentKgCommunity> result = kgCommunityMapper.selectPage(page, communityPageWrapper(context, keyword));
        return nodePage(result, this::communityNode);
    }

    private LambdaQueryWrapper<SuperAgentKgCommunity> communityPageWrapper(ArtifactContext context, String keyword) {
        LambdaQueryWrapper<SuperAgentKgCommunity> wrapper = new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .select(
                SuperAgentKgCommunity::getId,
                SuperAgentKgCommunity::getCommunityNo,
                SuperAgentKgCommunity::getTitle,
                SuperAgentKgCommunity::getSummary,
                SuperAgentKgCommunity::getEntityIdsJson,
                SuperAgentKgCommunity::getRelationIdsJson,
                SuperAgentKgCommunity::getEvidenceIdsJson
            )
            .eq(SuperAgentKgCommunity::getDocumentId, context.document().getId())
            .eq(SuperAgentKgCommunity::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode());
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentKgCommunity::getTitle, keyword)
                    .or().like(SuperAgentKgCommunity::getSummary, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentKgCommunity::getId, number)
                        .or().eq(SuperAgentKgCommunity::getCommunityNo, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentKgCommunity::getCommunityNo, SuperAgentKgCommunity::getId);
    }

    private NodePage queryEvidencePage(ArtifactContext context,
                                       DocumentRagArtifactNodePageQueryDto dto,
                                       String keyword,
                                       int pageNo,
                                       int pageSize) {
        if (context.indexTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentKgEvidence> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentKgEvidence> result = kgEvidenceMapper.selectPage(page, evidencePageWrapper(context, dto, keyword));
        return nodePage(result, this::evidenceNode);
    }

    private LambdaQueryWrapper<SuperAgentKgEvidence> evidencePageWrapper(ArtifactContext context,
                                                                          DocumentRagArtifactNodePageQueryDto dto,
                                                                          String keyword) {
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .select(
                SuperAgentKgEvidence::getId,
                SuperAgentKgEvidence::getEntityId,
                SuperAgentKgEvidence::getRelationId,
                SuperAgentKgEvidence::getChunkId,
                SuperAgentKgEvidence::getParentBlockId,
                SuperAgentKgEvidence::getPageNo,
                SuperAgentKgEvidence::getPageRange,
                SuperAgentKgEvidence::getSectionPath
            )
            .eq(SuperAgentKgEvidence::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEvidence::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (dto.getEntityId() != null) {
            wrapper.eq(SuperAgentKgEvidence::getEntityId, dto.getEntityId());
        }
        if (dto.getRelationId() != null) {
            wrapper.eq(SuperAgentKgEvidence::getRelationId, dto.getRelationId());
        }
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentKgEvidence::getSectionPath, keyword)
                    .or().like(SuperAgentKgEvidence::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentKgEvidence::getId, number)
                        .or().eq(SuperAgentKgEvidence::getEntityId, number)
                        .or().eq(SuperAgentKgEvidence::getRelationId, number)
                        .or().eq(SuperAgentKgEvidence::getChunkId, number)
                        .or().eq(SuperAgentKgEvidence::getParentBlockId, number);
                }
            });
        }
        return wrapper.orderByAsc(SuperAgentKgEvidence::getId);
    }

    private NodePage queryRaptorPage(ArtifactContext context,
                                     DocumentRagArtifactNodePageQueryDto dto,
                                     String keyword,
                                     int pageNo,
                                     int pageSize) {
        if (context.indexTaskId() == null) {
            return NodePage.empty();
        }
        Page<SuperAgentRaptorNode> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentRaptorNode> result = raptorNodeMapper.selectPage(page, raptorPageWrapper(context, dto, keyword));
        return nodePage(result, this::raptorNode);
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> raptorPageWrapper(ArtifactContext context,
                                                                       DocumentRagArtifactNodePageQueryDto dto,
                                                                       String keyword) {
        if (Boolean.TRUE.equals(dto.getRootOnly()) && dto.getParentNodeId() != null) {
            throw invalidRequest("rootOnly 与 parentNodeId 不能同时使用。");
        }
        LambdaQueryWrapper<SuperAgentRaptorNode> wrapper = new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .select(
                SuperAgentRaptorNode::getId,
                SuperAgentRaptorNode::getParentNodeId,
                SuperAgentRaptorNode::getNodeKey,
                SuperAgentRaptorNode::getNodeLevel,
                SuperAgentRaptorNode::getNodeNo,
                SuperAgentRaptorNode::getTitle,
                SuperAgentRaptorNode::getSectionPath,
                SuperAgentRaptorNode::getPageRange,
                SuperAgentRaptorNode::getChildNodeIdsJson
            )
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode());
        if (Boolean.TRUE.equals(dto.getRootOnly())) {
            wrapper.isNull(SuperAgentRaptorNode::getParentNodeId);
        } else if (dto.getParentNodeId() != null) {
            wrapper.eq(SuperAgentRaptorNode::getParentNodeId, dto.getParentNodeId());
        }
        if (dto.getLevel() != null) {
            wrapper.eq(SuperAgentRaptorNode::getNodeLevel, dto.getLevel());
        }
        Long number = numericKeyword(keyword);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(query -> {
                query.like(SuperAgentRaptorNode::getTitle, keyword)
                    .or().like(SuperAgentRaptorNode::getNodeKey, keyword)
                    .or().like(SuperAgentRaptorNode::getSectionPath, keyword)
                    .or().like(SuperAgentRaptorNode::getPageRange, keyword);
                if (number != null) {
                    query.or().eq(SuperAgentRaptorNode::getId, number)
                        .or().eq(SuperAgentRaptorNode::getNodeNo, number)
                        .or().eq(SuperAgentRaptorNode::getNodeLevel, number);
                }
            });
        }
        return wrapper.orderByDesc(SuperAgentRaptorNode::getNodeLevel)
            .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId);
    }

    private DocumentRagArtifactNodeDetailVo documentDetail(ArtifactContext context, Long sourceId) {
        if (!Objects.equals(context.document().getId(), sourceId)) {
            throw nodeNotFound();
        }
        return detailVo(
            context,
            documentNode(context.document()),
            context.document().getDocumentName(),
            attributes(
                attribute("文档 ID", context.document().getId()),
                attribute("文件名", context.document().getOriginalFileName()),
                attribute("字符数", context.document().getCharCount()),
                attribute("Token 数", context.document().getTokenCount()),
                attribute("解析任务", context.parseTaskId()),
                attribute("索引任务", context.indexTaskId())
            )
        );
    }

    private DocumentRagArtifactNodeDetailVo structureDetail(ArtifactContext context, Long sourceId) {
        SuperAgentDocumentStructureNode structure = structureNodeMapper.selectOne(
            structureIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (structure == null) {
            throw nodeNotFound();
        }
        return detailVo(context, structureNode(structure, structure.getContentText()), structure.getContentText(), attributes(
            attribute("结构编号", structure.getNodeNo()),
            attribute("结构类型", firstNotBlank(structure.getSyntaxNodeType(), value(structure.getNodeType()))),
            attribute("层级", structure.getDepth()),
            attribute("父节点 ID", structure.getParentNodeId()),
            attribute("节点编码", structure.getNodeCode()),
            attribute("标题", structure.getTitle()),
            attribute("规范路径", structure.getCanonicalPath()),
            attribute("章节", structure.getSectionPath()),
            attribute("源行范围", rangeText(structure.getSourceStartLine(), structure.getSourceEndLine())),
            attribute("语法节点 ID", structure.getSyntaxNodeId())
        ));
    }

    private DocumentRagArtifactNodeDetailVo blockDetail(ArtifactContext context, Long sourceId) {
        SuperAgentDocumentBlock block = context.parseTaskId() == null ? null : blockMapper.selectOne(
            blockIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (block == null) {
            throw nodeNotFound();
        }
        String content = firstNotBlank(block.getText(), block.getTableHtml(), block.getImageCaption(), block.getContentWithWeight());
        return detailVo(context, blockNode(block, content), content, attributes(
            attribute("解析块编号", block.getBlockNo()),
            attribute("解析块类型", block.getBlockType()),
            attribute("父级块 ID", block.getParentBlockId()),
            attribute("页码", firstNotBlank(block.getPageRange(), value(block.getPageNo()))),
            attribute("章节", block.getSectionPath()),
            attribute("结构路径", block.getCanonicalPath()),
            attribute("BBox", block.getBboxJson()),
            attribute("图片对象", block.getImageObjectName()),
            attribute("元数据", block.getMetadataJson())
        ));
    }

    private DocumentRagArtifactNodeDetailVo parentDetail(ArtifactContext context, Long sourceId) {
        SuperAgentDocumentParentBlock parent = context.indexTaskId() == null ? null : parentBlockMapper.selectOne(
            parentIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (parent == null) {
            throw nodeNotFound();
        }
        return detailVo(context, parentNode(parent, parent.getParentText()), parent.getParentText(), attributes(
            attribute("父级块编号", parent.getParentNo()),
            attribute("子块数量", parent.getChildCount()),
            attribute("子块范围", rangeText(parent.getStartChunkNo(), parent.getEndChunkNo())),
            attribute("字符数", parent.getCharCount()),
            attribute("Token 数", parent.getTokenCount()),
            attribute("页码", parent.getPageRange()),
            attribute("章节", parent.getSectionPath()),
            attribute("结构节点 ID", parent.getStructureNodeId()),
            attribute("来源解析块", parent.getSourceBlockIds())
        ));
    }

    private DocumentRagArtifactNodeDetailVo chunkDetail(ArtifactContext context, Long sourceId) {
        SuperAgentDocumentChunk chunk = context.indexTaskId() == null ? null : chunkMapper.selectOne(
            chunkIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (chunk == null) {
            throw nodeNotFound();
        }
        String content = firstNotBlank(chunk.getChunkText(), chunk.getContentWithWeight());
        DocumentVectorStatusEnum vectorStatus = DocumentVectorStatusEnum.getRc(chunk.getVectorStatus());
        return detailVo(context, chunkNode(chunk, content), content, attributes(
            attribute("子块编号", chunk.getChunkNo()),
            attribute("父级块 ID", chunk.getParentBlockId()),
            attribute("子块类型", chunk.getChunkType()),
            attribute("标题", chunk.getTitle()),
            attribute("字符数", chunk.getCharCount()),
            attribute("Token 数", chunk.getTokenCount()),
            attribute("向量状态", vectorStatus == null ? chunk.getVectorStatus() : vectorStatus.getMsg()),
            attribute("向量 ID", chunk.getVectorId()),
            attribute("页码", firstNotBlank(chunk.getPageRange(), value(chunk.getPageNo()))),
            attribute("章节", chunk.getSectionPath()),
            attribute("关键词", chunk.getKeywords()),
            attribute("典型问题", chunk.getQuestions()),
            attribute("来源解析块", chunk.getSourceBlockIds())
        ));
    }

    private DocumentRagArtifactNodeDetailVo tableDetail(ArtifactContext context, Long sourceId) {
        SuperAgentDocumentTable table = context.parseTaskId() == null ? null : tableMapper.selectOne(
            tableIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (table == null) {
            throw nodeNotFound();
        }
        return detailVo(context, tableNode(table, table.getTableHtml()), table.getTableHtml(), attributes(
            attribute("表格编号", table.getTableNo()),
            attribute("来源解析块 ID", table.getBlockId()),
            attribute("标题", table.getTitle()),
            attribute("规模", tableSize(table)),
            attribute("页码", firstNotBlank(table.getPageRange(), value(table.getPageNo()))),
            attribute("章节", table.getSectionPath()),
            attribute("BBox", table.getBboxJson()),
            attribute("元数据", table.getMetadataJson())
        ));
    }

    private DocumentRagArtifactNodeDetailVo evidenceDetail(ArtifactContext context, Long sourceId) {
        SuperAgentKgEvidence evidence = context.indexTaskId() == null ? null : kgEvidenceMapper.selectOne(
            evidenceIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (evidence == null) {
            throw nodeNotFound();
        }
        return detailVo(context, evidenceNode(evidence, evidence.getQuoteText()), evidence.getQuoteText(), attributes(
            attribute("实体 ID", evidence.getEntityId()),
            attribute("关系 ID", evidence.getRelationId()),
            attribute("子块 ID", evidence.getChunkId()),
            attribute("父级块 ID", evidence.getParentBlockId()),
            attribute("页码", firstNotBlank(evidence.getPageRange(), value(evidence.getPageNo()))),
            attribute("章节", evidence.getSectionPath()),
            attribute("BBox", evidence.getBboxJson()),
            attribute("元数据", evidence.getMetadataJson())
        ));
    }

    private DocumentRagArtifactNodeDetailVo entityDetail(ArtifactContext context, Long sourceId) {
        SuperAgentKgEntity entity = kgEntityMapper.selectOne(entityIdentityWrapper(context, sourceId).last("limit 1"));
        if (entity == null) {
            throw nodeNotFound();
        }
        return detailVo(context, entityNode(entity, entity.getDescription()), entity.getDescription(), attributes(
            attribute("实体 ID", entity.getId()),
            attribute("实体键", entity.getEntityKey()),
            attribute("名称", entity.getName()),
            attribute("规范名称", entity.getNormalizedName()),
            attribute("实体类型", entity.getEntityType()),
            attribute("元数据", entity.getMetadataJson())
        ));
    }

    private DocumentRagArtifactNodeDetailVo communityDetail(ArtifactContext context, Long sourceId) {
        SuperAgentKgCommunity community = kgCommunityMapper.selectOne(
            communityIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (community == null) {
            throw nodeNotFound();
        }
        return detailVo(context, communityNode(community, community.getSummary()), community.getSummary(), attributes(
            attribute("社区编号", community.getCommunityNo()),
            attribute("标题", community.getTitle()),
            attribute("实体数量", readLongList(community.getEntityIdsJson()).size()),
            attribute("关系数量", readLongList(community.getRelationIdsJson()).size()),
            attribute("证据数量", readLongList(community.getEvidenceIdsJson()).size()),
            attribute("元数据", community.getMetadataJson())
        ));
    }

    private DocumentRagArtifactNodeDetailVo raptorDetail(ArtifactContext context, Long sourceId) {
        SuperAgentRaptorNode node = context.indexTaskId() == null ? null : raptorNodeMapper.selectOne(
            raptorIdentityWrapper(context, sourceId).last("limit 1")
        );
        if (node == null) {
            throw nodeNotFound();
        }

        List<Long> parentSummaryIds = node.getParentNodeId() == null ? List.of() : List.of(node.getParentNodeId());
        List<Long> childSummaryIds = readLongList(node.getChildNodeIdsJson());
        List<Long> sourceParentIds = readLongList(node.getSourceParentBlockIdsJson());
        List<Long> sourceChunkIds = readLongList(node.getSourceChunkIdsJson());

        List<Long> summaryIds = new ArrayList<>(parentSummaryIds);
        summaryIds.addAll(sliceIds(childSummaryIds, 0, DETAIL_RELATED_LIMIT));
        Map<Long, SuperAgentRaptorNode> summaries = selectedRaptorDetailMap(context, summaryIds.stream().distinct().toList());
        Map<Long, SuperAgentDocumentParentBlock> sourceParents = selectedParentDetailMap(
            context,
            sliceIds(sourceParentIds, 0, DETAIL_RELATED_LIMIT)
        );
        Map<Long, SuperAgentDocumentChunk> sourceChunks = selectedChunkDetailMap(
            context,
            sliceIds(sourceChunkIds, 0, DETAIL_RELATED_LIMIT)
        );

        String scopeLabel = raptorScopeLabel(node.getScopeType());
        DocumentRagArtifactNodeDetailVo.PresentationItem presentation = new DocumentRagArtifactNodeDetailVo.PresentationItem(
            "RAPTOR",
            scopeLabel,
            readStringList(node.getKeywords(), DETAIL_KEYWORD_LIMIT),
            readStringList(node.getQuestions(), DETAIL_QUESTION_LIMIT),
            List.of(
                relatedGroup(
                    "PARENT_SUMMARY",
                    "上一级摘要",
                    "当前摘要所属的直接父级",
                    parentSummaryIds.size(),
                    orderedNodes(parentSummaryIds, summaries, this::raptorDetailNode)
                ),
                relatedGroup(
                    "CHILD_SUMMARIES",
                    "下一级摘要",
                    "由当前摘要继续拆分的直接子级",
                    childSummaryIds.size(),
                    orderedNodes(sliceIds(childSummaryIds, 0, DETAIL_RELATED_LIMIT), summaries, this::raptorDetailNode)
                ),
                relatedGroup(
                    "SOURCE_PARENTS",
                    "覆盖章节",
                    "生成本摘要时覆盖的回答上下文",
                    sourceParentIds.size(),
                    orderedNodes(sliceIds(sourceParentIds, 0, DETAIL_RELATED_LIMIT), sourceParents, this::parentDetailNode)
                ),
                relatedGroup(
                    "SOURCE_CHUNKS",
                    "来源片段",
                    "生成本摘要时使用的检索片段",
                    sourceChunkIds.size(),
                    orderedNodes(sliceIds(sourceChunkIds, 0, DETAIL_RELATED_LIMIT), sourceChunks, this::chunkDetailNode)
                )
            )
        );
        return detailVo(
            context,
            raptorNode(node, node.getSummary()),
            node.getSummary(),
            attributes(
                attribute("摘要层级", node.getNodeLevel() == null ? null : "第 " + node.getNodeLevel() + " 层"),
                attribute("覆盖范围", scopeLabel),
                attribute("章节位置", node.getSectionPath()),
                attribute("页码", node.getPageRange())
            ),
            presentation
        );
    }

    private DocumentRagArtifactNodeDetailVo.RelatedGroupItem relatedGroup(String key,
                                                                           String label,
                                                                           String description,
                                                                           int totalCount,
                                                                           List<DocumentRagSnapshotVo.ArtifactGraphNodeItem> nodes) {
        return new DocumentRagArtifactNodeDetailVo.RelatedGroupItem(key, label, description, totalCount, nodes);
    }

    private ArtifactFocus loadRelationFocus(ArtifactContext context, NodeIdentity identity) {
        Object source = switch (identity.type()) {
            case DOCUMENT -> Objects.equals(context.document().getId(), identity.sourceId()) ? context.document() : null;
            case STRUCTURE_NODE -> structureNodeMapper.selectOne(
                structureIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentDocumentStructureNode::getId,
                        SuperAgentDocumentStructureNode::getNodeNo,
                        SuperAgentDocumentStructureNode::getParentNodeId,
                        SuperAgentDocumentStructureNode::getDepth,
                        SuperAgentDocumentStructureNode::getNodeCode,
                        SuperAgentDocumentStructureNode::getTitle,
                        SuperAgentDocumentStructureNode::getCanonicalPath,
                        SuperAgentDocumentStructureNode::getSectionPath,
                        SuperAgentDocumentStructureNode::getSyntaxNodeType
                    )
                    .last("limit 1")
            );
            case PARSE_BLOCK -> context.parseTaskId() == null ? null : blockMapper.selectOne(
                blockIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentDocumentBlock::getId,
                        SuperAgentDocumentBlock::getBlockNo,
                        SuperAgentDocumentBlock::getBlockType,
                        SuperAgentDocumentBlock::getSectionPath,
                        SuperAgentDocumentBlock::getPageNo,
                        SuperAgentDocumentBlock::getPageRange,
                        SuperAgentDocumentBlock::getBboxJson
                    )
                    .last("limit 1")
            );
            case PARENT_BLOCK -> context.indexTaskId() == null ? null : parentBlockMapper.selectOne(
                parentIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentDocumentParentBlock::getId,
                        SuperAgentDocumentParentBlock::getParentNo,
                        SuperAgentDocumentParentBlock::getSectionPath,
                        SuperAgentDocumentParentBlock::getChildCount,
                        SuperAgentDocumentParentBlock::getStartChunkNo,
                        SuperAgentDocumentParentBlock::getEndChunkNo,
                        SuperAgentDocumentParentBlock::getPageRange,
                        SuperAgentDocumentParentBlock::getSourceBlockIds
                    )
                    .last("limit 1")
            );
            case CHILD_CHUNK -> context.indexTaskId() == null ? null : chunkMapper.selectOne(
                chunkIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentDocumentChunk::getId,
                        SuperAgentDocumentChunk::getParentBlockId,
                        SuperAgentDocumentChunk::getChunkNo,
                        SuperAgentDocumentChunk::getSectionPath,
                        SuperAgentDocumentChunk::getChunkType,
                        SuperAgentDocumentChunk::getTitle,
                        SuperAgentDocumentChunk::getVectorStatus,
                        SuperAgentDocumentChunk::getPageNo,
                        SuperAgentDocumentChunk::getPageRange,
                        SuperAgentDocumentChunk::getSourceBlockIds
                    )
                    .last("limit 1")
            );
            case TABLE -> context.parseTaskId() == null ? null : tableMapper.selectOne(
                tableIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentDocumentTable::getId,
                        SuperAgentDocumentTable::getBlockId,
                        SuperAgentDocumentTable::getTableNo,
                        SuperAgentDocumentTable::getSectionPath,
                        SuperAgentDocumentTable::getPageNo,
                        SuperAgentDocumentTable::getPageRange,
                        SuperAgentDocumentTable::getTitle,
                        SuperAgentDocumentTable::getRowCount,
                        SuperAgentDocumentTable::getColumnCount,
                        SuperAgentDocumentTable::getBboxJson
                    )
                    .last("limit 1")
            );
            case KG_ENTITY -> kgEntityMapper.selectOne(
                entityIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentKgEntity::getId,
                        SuperAgentKgEntity::getName,
                        SuperAgentKgEntity::getNormalizedName,
                        SuperAgentKgEntity::getEntityType,
                        SuperAgentKgEntity::getDescription
                    )
                    .last("limit 1")
            );
            case KG_COMMUNITY -> kgCommunityMapper.selectOne(
                communityIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentKgCommunity::getId,
                        SuperAgentKgCommunity::getCommunityNo,
                        SuperAgentKgCommunity::getTitle,
                        SuperAgentKgCommunity::getSummary,
                        SuperAgentKgCommunity::getEntityIdsJson,
                        SuperAgentKgCommunity::getRelationIdsJson,
                        SuperAgentKgCommunity::getEvidenceIdsJson
                    )
                    .last("limit 1")
            );
            case KG_EVIDENCE -> context.indexTaskId() == null ? null : kgEvidenceMapper.selectOne(
                evidenceIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentKgEvidence::getId,
                        SuperAgentKgEvidence::getEntityId,
                        SuperAgentKgEvidence::getRelationId,
                        SuperAgentKgEvidence::getChunkId,
                        SuperAgentKgEvidence::getParentBlockId,
                        SuperAgentKgEvidence::getPageNo,
                        SuperAgentKgEvidence::getPageRange,
                        SuperAgentKgEvidence::getSectionPath
                    )
                    .last("limit 1")
            );
            case RAPTOR_NODE -> context.indexTaskId() == null ? null : raptorNodeMapper.selectOne(
                raptorIdentityWrapper(context, identity.sourceId())
                    .select(
                        SuperAgentRaptorNode::getId,
                        SuperAgentRaptorNode::getParentNodeId,
                        SuperAgentRaptorNode::getNodeKey,
                        SuperAgentRaptorNode::getNodeLevel,
                        SuperAgentRaptorNode::getNodeNo,
                        SuperAgentRaptorNode::getTitle,
                        SuperAgentRaptorNode::getSectionPath,
                        SuperAgentRaptorNode::getPageRange,
                        SuperAgentRaptorNode::getSourceChunkIdsJson,
                        SuperAgentRaptorNode::getSourceParentBlockIdsJson
                    )
                    .last("limit 1")
            );
        };
        if (source == null) {
            throw nodeNotFound();
        }
        return new ArtifactFocus(identity, source, nodeFromSource(identity.type(), source));
    }

    private List<RelationSegment> relationSegments(ArtifactContext context,
                                                   ArtifactFocus focus,
                                                   RelationDirection direction) {
        return switch (focus.identity().type()) {
            case DOCUMENT -> documentRelationSegments(context, focus, direction);
            case STRUCTURE_NODE -> structureRelationSegments(context, focus, direction);
            case PARSE_BLOCK -> blockRelationSegments(context, focus, direction);
            case PARENT_BLOCK -> parentRelationSegments(context, focus, direction);
            case TABLE -> tableRelationSegments(context, focus, direction);
            case CHILD_CHUNK -> chunkRelationSegments(context, focus, direction);
            case KG_ENTITY, KG_COMMUNITY -> List.of();
            case KG_EVIDENCE -> evidenceRelationSegments(context, focus, direction);
            case RAPTOR_NODE -> raptorRelationSegments(context, focus, direction);
        };
    }

    private List<RelationSegment> documentRelationSegments(ArtifactContext context,
                                                           ArtifactFocus focus,
                                                           RelationDirection direction) {
        if (direction == RelationDirection.UPSTREAM || context.parseTaskId() == null) {
            return List.of();
        }
        long total = blockMapper.selectCount(blockRelationBaseWrapper(context));
        return List.of(new RelationSegment(total, (offset, limit) -> blockMapper.selectList(
                blockRelationBaseWrapper(context)
                    .select(
                        SuperAgentDocumentBlock::getId,
                        SuperAgentDocumentBlock::getBlockNo,
                        SuperAgentDocumentBlock::getBlockType,
                        SuperAgentDocumentBlock::getSectionPath,
                        SuperAgentDocumentBlock::getPageNo,
                        SuperAgentDocumentBlock::getPageRange,
                        SuperAgentDocumentBlock::getBboxJson
                    )
                    .orderByAsc(SuperAgentDocumentBlock::getBlockNo, SuperAgentDocumentBlock::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(block -> relationItem(focus.node(), blockNode(block), "document-block", "解析块", ""))
            .toList()));
    }

    private List<RelationSegment> structureRelationSegments(ArtifactContext context,
                                                             ArtifactFocus focus,
                                                             RelationDirection direction) {
        SuperAgentDocumentStructureNode structure = (SuperAgentDocumentStructureNode) focus.source();
        if (direction == RelationDirection.UPSTREAM) {
            if (structure.getParentNodeId() == null) {
                return List.of();
            }
            SuperAgentDocumentStructureNode parent = structureNodeMapper.selectOne(
                structureIdentityWrapper(context, structure.getParentNodeId()).last("limit 1")
            );
            return parent == null
                ? List.of()
                : List.of(singleRelationSegment(() -> relationItem(
                    structureNode(parent), focus.node(), "structure-child", "结构层级", "", true
                )));
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> base = new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentStructureNode::getParseTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentStructureNode::getParentNodeId, structure.getId())
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode());
        long total = structureNodeMapper.selectCount(base);
        return List.of(new RelationSegment(total, (offset, limit) -> structureNodeMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
                    .select(
                        SuperAgentDocumentStructureNode::getId,
                        SuperAgentDocumentStructureNode::getNodeNo,
                        SuperAgentDocumentStructureNode::getParentNodeId,
                        SuperAgentDocumentStructureNode::getDepth,
                        SuperAgentDocumentStructureNode::getNodeCode,
                        SuperAgentDocumentStructureNode::getTitle,
                        SuperAgentDocumentStructureNode::getCanonicalPath,
                        SuperAgentDocumentStructureNode::getSectionPath,
                        SuperAgentDocumentStructureNode::getSyntaxNodeType
                    )
                    .eq(SuperAgentDocumentStructureNode::getDocumentId, context.document().getId())
                    .eq(SuperAgentDocumentStructureNode::getParseTaskId, context.parseTaskId())
                    .eq(SuperAgentDocumentStructureNode::getParentNodeId, structure.getId())
                    .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(child -> relationItem(focus.node(), structureNode(child), "structure-child", "结构层级", ""))
            .toList()));
    }

    private List<RelationSegment> blockRelationSegments(ArtifactContext context,
                                                        ArtifactFocus focus,
                                                        RelationDirection direction) {
        SuperAgentDocumentBlock block = (SuperAgentDocumentBlock) focus.source();
        if (direction == RelationDirection.UPSTREAM) {
            return List.of(singleRelationSegment(() -> relationItem(
                documentNode(context.document()), focus.node(), "document-block", "解析块", "", true
            )));
        }
        List<RelationSegment> segments = new ArrayList<>();
        if (context.indexTaskId() != null) {
            segments.add(parentBySourceBlockSegment(context, block.getId(), focus.node()));
        }
        if (context.parseTaskId() != null) {
            segments.add(tableByBlockSegment(context, block.getId(), focus.node()));
        }
        if (context.indexTaskId() != null) {
            segments.add(chunkBySourceBlockSegment(context, block.getId(), focus.node()));
        }
        return nonEmptySegments(segments);
    }

    private List<RelationSegment> parentRelationSegments(ArtifactContext context,
                                                         ArtifactFocus focus,
                                                         RelationDirection direction) {
        SuperAgentDocumentParentBlock parent = (SuperAgentDocumentParentBlock) focus.source();
        if (direction == RelationDirection.UPSTREAM) {
            return List.of(blockIdsSegment(context, readLongList(parent.getSourceBlockIds()), focus.node(),
                "block-parent", "组成父块"));
        }
        return nonEmptySegments(List.of(
            chunkByParentSegment(context, parent.getId(), focus.node()),
            evidenceByParentSegment(context, parent.getId(), focus.node()),
            raptorBySourceParentSegment(context, parent.getId(), focus.node())
        ));
    }

    private List<RelationSegment> chunkRelationSegments(ArtifactContext context,
                                                        ArtifactFocus focus,
                                                        RelationDirection direction) {
        SuperAgentDocumentChunk chunk = (SuperAgentDocumentChunk) focus.source();
        if (direction == RelationDirection.UPSTREAM) {
            List<RelationSegment> segments = new ArrayList<>();
            if (chunk.getParentBlockId() != null) {
                segments.add(parentIdSegment(context, chunk.getParentBlockId(), focus.node(), "parent-chunk", "切成子块"));
            }
            segments.add(blockIdsSegment(context, readLongList(chunk.getSourceBlockIds()), focus.node(),
                "block-chunk", "来源 block"));
            return nonEmptySegments(segments);
        }
        return nonEmptySegments(List.of(
            evidenceByChunkSegment(context, chunk.getId(), focus.node()),
            raptorBySourceChunkSegment(context, chunk.getId(), focus.node())
        ));
    }

    private List<RelationSegment> tableRelationSegments(ArtifactContext context,
                                                        ArtifactFocus focus,
                                                        RelationDirection direction) {
        SuperAgentDocumentTable table = (SuperAgentDocumentTable) focus.source();
        if (direction == RelationDirection.DOWNSTREAM || table.getBlockId() == null) {
            return List.of();
        }
        return List.of(blockIdSegment(context, table.getBlockId(), focus.node(), "block-table", "抽取表格"));
    }

    private List<RelationSegment> evidenceRelationSegments(ArtifactContext context,
                                                           ArtifactFocus focus,
                                                           RelationDirection direction) {
        SuperAgentKgEvidence evidence = (SuperAgentKgEvidence) focus.source();
        if (direction == RelationDirection.DOWNSTREAM) {
            return List.of();
        }
        List<RelationSegment> segments = new ArrayList<>();
        if (evidence.getParentBlockId() != null) {
            segments.add(parentIdSegment(context, evidence.getParentBlockId(), focus.node(), "parent-kg", "图谱来源父级块"));
        }
        if (evidence.getChunkId() != null) {
            segments.add(chunkIdSegment(context, evidence.getChunkId(), focus.node(), "chunk-kg", "支撑知识图谱"));
        }
        return nonEmptySegments(segments);
    }

    private List<RelationSegment> raptorRelationSegments(ArtifactContext context,
                                                         ArtifactFocus focus,
                                                         RelationDirection direction) {
        SuperAgentRaptorNode raptor = (SuperAgentRaptorNode) focus.source();
        if (direction == RelationDirection.DOWNSTREAM) {
            return List.of(raptorChildrenSegment(context, raptor.getId(), focus.node()));
        }
        List<RelationSegment> segments = new ArrayList<>();
        if (raptor.getParentNodeId() != null) {
            segments.add(raptorIdSegment(context, raptor.getParentNodeId(), focus.node(), "raptor-child", "摘要下钻"));
        }
        segments.add(chunkIdsSegment(context, readLongList(raptor.getSourceChunkIdsJson()), focus.node(),
            "chunk-raptor", "摘要来源"));
        segments.add(parentIdsSegment(context, readLongList(raptor.getSourceParentBlockIdsJson()), focus.node(),
            "parent-raptor", "摘要来源"));
        return nonEmptySegments(segments);
    }

    private RelationSegment parentBySourceBlockSegment(ArtifactContext context,
                                                       Long blockId,
                                                       DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = parentBlockMapper.selectCount(parentBySourceBlockWrapper(context, blockId));
        return new RelationSegment(total, (offset, limit) -> parentBlockMapper.selectList(
                parentBySourceBlockWrapper(context, blockId)
                    .select(
                        SuperAgentDocumentParentBlock::getId,
                        SuperAgentDocumentParentBlock::getParentNo,
                        SuperAgentDocumentParentBlock::getSectionPath,
                        SuperAgentDocumentParentBlock::getChildCount,
                        SuperAgentDocumentParentBlock::getStartChunkNo,
                        SuperAgentDocumentParentBlock::getEndChunkNo,
                        SuperAgentDocumentParentBlock::getPageRange
                    )
                    .orderByAsc(SuperAgentDocumentParentBlock::getParentNo, SuperAgentDocumentParentBlock::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(parent -> relationItem(focusNode, parentNode(parent), "block-parent", "组成父块", parent.getSectionPath()))
            .toList());
    }

    private RelationSegment chunkBySourceBlockSegment(ArtifactContext context,
                                                      Long blockId,
                                                      DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = chunkMapper.selectCount(chunkBySourceBlockWrapper(context, blockId));
        return new RelationSegment(total, (offset, limit) -> chunkMapper.selectList(
                chunkBySourceBlockWrapper(context, blockId)
                    .select(
                        SuperAgentDocumentChunk::getId,
                        SuperAgentDocumentChunk::getParentBlockId,
                        SuperAgentDocumentChunk::getChunkNo,
                        SuperAgentDocumentChunk::getSectionPath,
                        SuperAgentDocumentChunk::getChunkType,
                        SuperAgentDocumentChunk::getTitle,
                        SuperAgentDocumentChunk::getVectorStatus,
                        SuperAgentDocumentChunk::getPageNo,
                        SuperAgentDocumentChunk::getPageRange
                    )
                    .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(chunk -> relationItem(focusNode, chunkNode(chunk), "block-chunk", "来源 block", ""))
            .toList());
    }

    private RelationSegment tableByBlockSegment(ArtifactContext context,
                                                Long blockId,
                                                DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        LambdaQueryWrapper<SuperAgentDocumentTable> countWrapper = tableByBlockWrapper(context, blockId);
        long total = tableMapper.selectCount(countWrapper);
        return new RelationSegment(total, (offset, limit) -> tableMapper.selectList(
                tableByBlockWrapper(context, blockId)
                    .select(
                        SuperAgentDocumentTable::getId,
                        SuperAgentDocumentTable::getBlockId,
                        SuperAgentDocumentTable::getTableNo,
                        SuperAgentDocumentTable::getSectionPath,
                        SuperAgentDocumentTable::getPageNo,
                        SuperAgentDocumentTable::getPageRange,
                        SuperAgentDocumentTable::getTitle,
                        SuperAgentDocumentTable::getRowCount,
                        SuperAgentDocumentTable::getColumnCount,
                        SuperAgentDocumentTable::getBboxJson
                    )
                    .orderByAsc(SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(table -> relationItem(focusNode, tableNode(table), "block-table", "抽取表格", ""))
            .toList());
    }

    private RelationSegment chunkByParentSegment(ArtifactContext context,
                                                 Long parentId,
                                                 DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = chunkMapper.selectCount(chunkByParentWrapper(context, parentId));
        return new RelationSegment(total, (offset, limit) -> chunkMapper.selectList(
                chunkByParentWrapper(context, parentId)
                    .select(
                        SuperAgentDocumentChunk::getId,
                        SuperAgentDocumentChunk::getParentBlockId,
                        SuperAgentDocumentChunk::getChunkNo,
                        SuperAgentDocumentChunk::getSectionPath,
                        SuperAgentDocumentChunk::getChunkType,
                        SuperAgentDocumentChunk::getTitle,
                        SuperAgentDocumentChunk::getVectorStatus,
                        SuperAgentDocumentChunk::getPageNo,
                        SuperAgentDocumentChunk::getPageRange
                    )
                    .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(chunk -> relationItem(focusNode, chunkNode(chunk), "parent-chunk", "切成子块", ""))
            .toList());
    }

    private RelationSegment evidenceByParentSegment(ArtifactContext context,
                                                    Long parentId,
                                                    DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = kgEvidenceMapper.selectCount(evidenceByParentWrapper(context, parentId));
        return new RelationSegment(total, (offset, limit) -> kgEvidenceMapper.selectList(
                evidenceByParentWrapper(context, parentId)
                    .select(
                        SuperAgentKgEvidence::getId,
                        SuperAgentKgEvidence::getEntityId,
                        SuperAgentKgEvidence::getRelationId,
                        SuperAgentKgEvidence::getChunkId,
                        SuperAgentKgEvidence::getParentBlockId,
                        SuperAgentKgEvidence::getPageNo,
                        SuperAgentKgEvidence::getPageRange,
                        SuperAgentKgEvidence::getSectionPath
                    )
                    .orderByAsc(SuperAgentKgEvidence::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(evidence -> relationItem(focusNode, evidenceNode(evidence), "parent-kg", "图谱来源父级块", ""))
            .toList());
    }

    private RelationSegment evidenceByChunkSegment(ArtifactContext context,
                                                   Long chunkId,
                                                   DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = kgEvidenceMapper.selectCount(evidenceByChunkWrapper(context, chunkId));
        return new RelationSegment(total, (offset, limit) -> kgEvidenceMapper.selectList(
                evidenceByChunkWrapper(context, chunkId)
                    .select(
                        SuperAgentKgEvidence::getId,
                        SuperAgentKgEvidence::getEntityId,
                        SuperAgentKgEvidence::getRelationId,
                        SuperAgentKgEvidence::getChunkId,
                        SuperAgentKgEvidence::getParentBlockId,
                        SuperAgentKgEvidence::getPageNo,
                        SuperAgentKgEvidence::getPageRange,
                        SuperAgentKgEvidence::getSectionPath
                    )
                    .orderByAsc(SuperAgentKgEvidence::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(evidence -> relationItem(focusNode, evidenceNode(evidence), "chunk-kg", "支撑知识图谱", ""))
            .toList());
    }

    private RelationSegment raptorBySourceParentSegment(ArtifactContext context,
                                                        Long parentId,
                                                        DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = raptorNodeMapper.selectCount(raptorBySourceParentWrapper(context, parentId));
        return new RelationSegment(total, (offset, limit) -> raptorNodeMapper.selectList(
                selectRaptorListColumns(raptorBySourceParentWrapper(context, parentId))
                    .orderByDesc(SuperAgentRaptorNode::getNodeLevel)
                    .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(node -> relationItem(focusNode, raptorNode(node), "parent-raptor", "摘要来源", ""))
            .toList());
    }

    private RelationSegment raptorBySourceChunkSegment(ArtifactContext context,
                                                       Long chunkId,
                                                       DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = raptorNodeMapper.selectCount(raptorBySourceChunkWrapper(context, chunkId));
        return new RelationSegment(total, (offset, limit) -> raptorNodeMapper.selectList(
                selectRaptorListColumns(raptorBySourceChunkWrapper(context, chunkId))
                    .orderByDesc(SuperAgentRaptorNode::getNodeLevel)
                    .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(node -> relationItem(focusNode, raptorNode(node), "chunk-raptor", "摘要来源", ""))
            .toList());
    }

    private RelationSegment raptorChildrenSegment(ArtifactContext context,
                                                  Long parentNodeId,
                                                  DocumentRagSnapshotVo.ArtifactGraphNodeItem focusNode) {
        long total = raptorNodeMapper.selectCount(raptorChildrenWrapper(context, parentNodeId));
        return new RelationSegment(total, (offset, limit) -> raptorNodeMapper.selectList(
                selectRaptorListColumns(raptorChildrenWrapper(context, parentNodeId))
                    .orderByDesc(SuperAgentRaptorNode::getNodeLevel)
                    .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId)
                    .last(limitClause(offset, limit))
            ).stream()
            .map(node -> relationItem(focusNode, raptorNode(node), "raptor-child", "摘要下钻", ""))
            .toList());
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> selectRaptorListColumns(
        LambdaQueryWrapper<SuperAgentRaptorNode> wrapper) {
        return wrapper.select(
            SuperAgentRaptorNode::getId,
            SuperAgentRaptorNode::getParentNodeId,
            SuperAgentRaptorNode::getNodeKey,
            SuperAgentRaptorNode::getNodeLevel,
            SuperAgentRaptorNode::getNodeNo,
            SuperAgentRaptorNode::getTitle,
            SuperAgentRaptorNode::getSectionPath,
            SuperAgentRaptorNode::getPageRange
        );
    }

    private RelationSegment blockIdsSegment(ArtifactContext context,
                                            List<Long> ids,
                                            DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                            String edgeType,
                                            String label) {
        return new RelationSegment(ids.size(), (offset, limit) -> orderedNodes(
            sliceIds(ids, offset, limit),
            selectedBlockMap(context, sliceIds(ids, offset, limit)),
            this::blockNode
        ).stream().map(node -> relationItem(node, target, edgeType, label, target.getSectionPath(), true)).toList());
    }

    private RelationSegment parentIdsSegment(ArtifactContext context,
                                             List<Long> ids,
                                             DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                             String edgeType,
                                             String label) {
        return new RelationSegment(ids.size(), (offset, limit) -> orderedNodes(
            sliceIds(ids, offset, limit),
            selectedParentMap(context, sliceIds(ids, offset, limit)),
            this::parentNode
        ).stream().map(node -> relationItem(node, target, edgeType, label, "", true)).toList());
    }

    private RelationSegment chunkIdsSegment(ArtifactContext context,
                                            List<Long> ids,
                                            DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                            String edgeType,
                                            String label) {
        return new RelationSegment(ids.size(), (offset, limit) -> orderedNodes(
            sliceIds(ids, offset, limit),
            selectedChunkMap(context, sliceIds(ids, offset, limit)),
            this::chunkNode
        ).stream().map(node -> relationItem(node, target, edgeType, label, "", true)).toList());
    }

    private RelationSegment blockIdSegment(ArtifactContext context,
                                           Long id,
                                           DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                           String edgeType,
                                           String label) {
        return blockIdsSegment(context, id == null ? List.of() : List.of(id), target, edgeType, label);
    }

    private RelationSegment parentIdSegment(ArtifactContext context,
                                            Long id,
                                            DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                            String edgeType,
                                            String label) {
        return parentIdsSegment(context, id == null ? List.of() : List.of(id), target, edgeType, label);
    }

    private RelationSegment chunkIdSegment(ArtifactContext context,
                                           Long id,
                                           DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                           String edgeType,
                                           String label) {
        return chunkIdsSegment(context, id == null ? List.of() : List.of(id), target, edgeType, label);
    }

    private RelationSegment raptorIdSegment(ArtifactContext context,
                                            Long id,
                                            DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
                                            String edgeType,
                                            String label) {
        List<Long> ids = id == null ? List.of() : List.of(id);
        return new RelationSegment(ids.size(), (offset, limit) -> orderedNodes(
            sliceIds(ids, offset, limit),
            selectedRaptorMap(context, sliceIds(ids, offset, limit)),
            this::raptorNode
        ).stream().map(node -> relationItem(node, target, edgeType, label, "", true)).toList());
    }

    private RelationSegment singleRelationSegment(RelationSupplier supplier) {
        return new RelationSegment(1L, (offset, limit) -> offset == 0 && limit > 0 ? List.of(supplier.get()) : List.of());
    }

    private List<DocumentRagArtifactRelationPageVo.RelationItem> readRelationPage(List<RelationSegment> segments,
                                                                                  int pageNo,
                                                                                  int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        int remaining = pageSize;
        List<DocumentRagArtifactRelationPageVo.RelationItem> records = new ArrayList<>(pageSize);
        for (RelationSegment segment : segments) {
            if (remaining <= 0) {
                break;
            }
            if (offset >= segment.total()) {
                offset -= segment.total();
                continue;
            }
            int limit = (int) Math.min(remaining, segment.total() - offset);
            List<DocumentRagArtifactRelationPageVo.RelationItem> segmentRecords = segment.loader().load(offset, limit);
            records.addAll(segmentRecords);
            remaining -= segmentRecords.size();
            offset = 0L;
        }
        return records;
    }

    private List<RelationSegment> nonEmptySegments(List<RelationSegment> segments) {
        return segments.stream().filter(segment -> segment != null && segment.total() > 0L).toList();
    }

    private LambdaQueryWrapper<SuperAgentDocumentBlock> blockRelationBaseWrapper(ArtifactContext context) {
        return new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentBlock::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentParentBlock> parentBySourceBlockWrapper(ArtifactContext context, Long blockId) {
        return new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentParentBlock::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
            .apply("JSON_VALID(source_block_ids) AND JSON_CONTAINS(source_block_ids, CAST({0} AS JSON))", String.valueOf(blockId));
    }

    private LambdaQueryWrapper<SuperAgentDocumentChunk> chunkBySourceBlockWrapper(ArtifactContext context, Long blockId) {
        return new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .apply("JSON_VALID(source_block_ids) AND JSON_CONTAINS(source_block_ids, CAST({0} AS JSON))", String.valueOf(blockId));
    }

    private LambdaQueryWrapper<SuperAgentDocumentTable> tableByBlockWrapper(ArtifactContext context, Long blockId) {
        return new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTable::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTable::getBlockId, blockId)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentChunk> chunkByParentWrapper(ArtifactContext context, Long parentId) {
        return new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getParentBlockId, parentId)
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentKgEvidence> evidenceByParentWrapper(ArtifactContext context, Long parentId) {
        return new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEvidence::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEvidence::getParentBlockId, parentId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentKgEvidence> evidenceByChunkWrapper(ArtifactContext context, Long chunkId) {
        return new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEvidence::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEvidence::getChunkId, chunkId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> raptorBySourceParentWrapper(ArtifactContext context, Long parentId) {
        return new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .apply("JSON_VALID(source_parent_block_ids_json) AND JSON_CONTAINS(source_parent_block_ids_json, CAST({0} AS JSON))", String.valueOf(parentId));
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> raptorBySourceChunkWrapper(ArtifactContext context, Long chunkId) {
        return new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .apply("JSON_VALID(source_chunk_ids_json) AND JSON_CONTAINS(source_chunk_ids_json, CAST({0} AS JSON))", String.valueOf(chunkId));
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> raptorChildrenWrapper(ArtifactContext context, Long parentNodeId) {
        return new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getParentNodeId, parentNodeId)
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode());
    }

    private Map<Long, SuperAgentDocumentBlock> selectedBlockMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.parseTaskId() == null) {
            return Map.of();
        }
        return mapById(blockMapper.selectList(blockRelationBaseWrapper(context)
            .select(
                SuperAgentDocumentBlock::getId,
                SuperAgentDocumentBlock::getBlockNo,
                SuperAgentDocumentBlock::getBlockType,
                SuperAgentDocumentBlock::getSectionPath,
                SuperAgentDocumentBlock::getPageNo,
                SuperAgentDocumentBlock::getPageRange,
                SuperAgentDocumentBlock::getBboxJson
            )
            .in(SuperAgentDocumentBlock::getId, ids)), SuperAgentDocumentBlock::getId);
    }

    private Map<Long, SuperAgentDocumentParentBlock> selectedParentMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .select(
                SuperAgentDocumentParentBlock::getId,
                SuperAgentDocumentParentBlock::getParentNo,
                SuperAgentDocumentParentBlock::getSectionPath,
                SuperAgentDocumentParentBlock::getChildCount,
                SuperAgentDocumentParentBlock::getStartChunkNo,
                SuperAgentDocumentParentBlock::getEndChunkNo,
                SuperAgentDocumentParentBlock::getPageRange
            )
            .eq(SuperAgentDocumentParentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentParentBlock::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentParentBlock::getId, ids)), SuperAgentDocumentParentBlock::getId);
    }

    private Map<Long, SuperAgentDocumentChunk> selectedChunkMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .select(
                SuperAgentDocumentChunk::getId,
                SuperAgentDocumentChunk::getParentBlockId,
                SuperAgentDocumentChunk::getChunkNo,
                SuperAgentDocumentChunk::getSectionPath,
                SuperAgentDocumentChunk::getChunkType,
                SuperAgentDocumentChunk::getTitle,
                SuperAgentDocumentChunk::getVectorStatus,
                SuperAgentDocumentChunk::getPageNo,
                SuperAgentDocumentChunk::getPageRange
            )
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentChunk::getId, ids)), SuperAgentDocumentChunk::getId);
    }

    private Map<Long, SuperAgentRaptorNode> selectedRaptorMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(raptorNodeMapper.selectList(selectRaptorListColumns(new LambdaQueryWrapper<SuperAgentRaptorNode>())
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentRaptorNode::getId, ids)), SuperAgentRaptorNode::getId);
    }

    private Map<Long, SuperAgentRaptorNode> selectedRaptorDetailMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .select(
                SuperAgentRaptorNode::getId,
                SuperAgentRaptorNode::getNodeLevel,
                SuperAgentRaptorNode::getNodeNo,
                SuperAgentRaptorNode::getTitle,
                SuperAgentRaptorNode::getSummary,
                SuperAgentRaptorNode::getSectionPath,
                SuperAgentRaptorNode::getPageRange,
                SuperAgentRaptorNode::getChildNodeIdsJson
            )
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentRaptorNode::getId, ids)), SuperAgentRaptorNode::getId);
    }

    private Map<Long, SuperAgentDocumentParentBlock> selectedParentDetailMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .select(
                SuperAgentDocumentParentBlock::getId,
                SuperAgentDocumentParentBlock::getParentNo,
                SuperAgentDocumentParentBlock::getSectionPath,
                SuperAgentDocumentParentBlock::getPageRange,
                SuperAgentDocumentParentBlock::getParentText,
                SuperAgentDocumentParentBlock::getChildCount
            )
            .eq(SuperAgentDocumentParentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentParentBlock::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentParentBlock::getId, ids)), SuperAgentDocumentParentBlock::getId);
    }

    private Map<Long, SuperAgentDocumentChunk> selectedChunkDetailMap(ArtifactContext context, List<Long> ids) {
        if (ids.isEmpty() || context.indexTaskId() == null) {
            return Map.of();
        }
        return mapById(chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .select(
                SuperAgentDocumentChunk::getId,
                SuperAgentDocumentChunk::getChunkNo,
                SuperAgentDocumentChunk::getChunkType,
                SuperAgentDocumentChunk::getTitle,
                SuperAgentDocumentChunk::getSectionPath,
                SuperAgentDocumentChunk::getPageNo,
                SuperAgentDocumentChunk::getPageRange,
                SuperAgentDocumentChunk::getChunkText
            )
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentChunk::getId, ids)), SuperAgentDocumentChunk::getId);
    }

    private <T> Map<Long, T> mapById(List<T> records, Function<T, Long> idExtractor) {
        Map<Long, T> result = new LinkedHashMap<>();
        records.forEach(record -> result.putIfAbsent(idExtractor.apply(record), record));
        return result;
    }

    private <T> List<DocumentRagSnapshotVo.ArtifactGraphNodeItem> orderedNodes(
        List<Long> ids,
        Map<Long, T> records,
        Function<T, DocumentRagSnapshotVo.ArtifactGraphNodeItem> mapper) {
        return ids.stream()
            .map(records::get)
            .filter(Objects::nonNull)
            .map(mapper)
            .toList();
    }

    private List<Long> sliceIds(List<Long> ids, long offset, int limit) {
        if (ids.isEmpty() || offset >= ids.size() || limit <= 0) {
            return List.of();
        }
        int from = (int) Math.max(0L, offset);
        int to = Math.min(ids.size(), from + limit);
        return ids.subList(from, to);
    }

    private DocumentRagArtifactRelationPageVo.RelationItem relationItem(
        DocumentRagSnapshotVo.ArtifactGraphNodeItem source,
        DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
        String edgeType,
        String label,
        String detail) {
        return relationItem(source, target, edgeType, label, detail, false);
    }

    private DocumentRagArtifactRelationPageVo.RelationItem relationItem(
        DocumentRagSnapshotVo.ArtifactGraphNodeItem source,
        DocumentRagSnapshotVo.ArtifactGraphNodeItem target,
        String edgeType,
        String label,
        String detail,
        boolean sourceIsNeighbor) {
        DocumentRagSnapshotVo.ArtifactGraphEdgeItem edge = new DocumentRagSnapshotVo.ArtifactGraphEdgeItem(
            edgeType + ":" + source.getNodeId() + "->" + target.getNodeId(),
            source.getNodeId(),
            target.getNodeId(),
            edgeType,
            label,
            StrUtil.blankToDefault(detail, "")
        );
        DocumentRagSnapshotVo.ArtifactGraphNodeItem neighbor = sourceIsNeighbor ? source : target;
        return new DocumentRagArtifactRelationPageVo.RelationItem(edge, neighbor);
    }

    private ArtifactContext resolveContext(Long documentId, Long parseTaskId, Long indexTaskId) {
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(),
                DocumentManageCode.DOCUMENT_NOT_FOUND.getMsg());
        }
        if (parseTaskId == null) {
            throw invalidRequest("parseTaskId 不能为空，请使用快照返回的任务版本。");
        }
        if (indexTaskId == null) {
            throw invalidRequest("indexTaskId 不能为空，请使用快照返回的任务版本。");
        }

        SuperAgentDocumentTask parseTask = requireTask(
            document.getId(),
            parseTaskId,
            DocumentTaskTypeEnum.PARSE_ROUTE,
            "解析任务"
        );
        SuperAgentDocumentTask indexTask = requireTask(
            document.getId(),
            indexTaskId,
            DocumentTaskTypeEnum.BUILD_INDEX,
            "索引任务"
        );
        if (!Objects.equals(indexTask.getSourceParseTaskId(), parseTask.getId())) {
            throw invalidRequest("索引任务 sourceParseTaskId 与请求的 parseTaskId 不一致。");
        }
        return new ArtifactContext(document, parseTask.getId(), indexTask.getId());
    }

    private SuperAgentDocumentTask requireTask(Long documentId,
                                               Long taskId,
                                               DocumentTaskTypeEnum taskType,
                                               String taskLabel) {
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null
            || !Objects.equals(task.getDocumentId(), documentId)
            || !Objects.equals(task.getTaskType(), taskType.getCode())
            || !Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())) {
            throw invalidRequest(taskLabel + "不存在、未成功、类型错误或不属于当前文档。");
        }
        return task;
    }

    private LambdaQueryWrapper<SuperAgentDocumentBlock> blockIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getId, sourceId)
            .eq(SuperAgentDocumentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentBlock::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentStructureNode> structureIdentityWrapper(ArtifactContext context,
                                                                                           Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getId, sourceId)
            .eq(SuperAgentDocumentStructureNode::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentStructureNode::getParseTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentParentBlock> parentIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getId, sourceId)
            .eq(SuperAgentDocumentParentBlock::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentParentBlock::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentChunk> chunkIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getId, sourceId)
            .eq(SuperAgentDocumentChunk::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentChunk::getTaskId, context.indexTaskId())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentTable> tableIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getId, sourceId)
            .eq(SuperAgentDocumentTable::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTable::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentTableRow> tableRowWrapper(ArtifactContext context, Long tableId) {
        return new LambdaQueryWrapper<SuperAgentDocumentTableRow>()
            .eq(SuperAgentDocumentTableRow::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTableRow::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTableRow::getTableId, tableId)
            .eq(SuperAgentDocumentTableRow::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentTableColumn> tableColumnWrapper(ArtifactContext context,
                                                                                  Long tableId) {
        return new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
            .eq(SuperAgentDocumentTableColumn::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTableColumn::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTableColumn::getTableId, tableId)
            .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentDocumentTableCell> tableCellWrapper(ArtifactContext context,
                                                                              Long tableId,
                                                                              List<Long> rowIds,
                                                                              List<Long> columnIds) {
        return new LambdaQueryWrapper<SuperAgentDocumentTableCell>()
            .eq(SuperAgentDocumentTableCell::getDocumentId, context.document().getId())
            .eq(SuperAgentDocumentTableCell::getTaskId, context.parseTaskId())
            .eq(SuperAgentDocumentTableCell::getTableId, tableId)
            .eq(SuperAgentDocumentTableCell::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentTableCell::getRowId, rowIds)
            .in(SuperAgentDocumentTableCell::getColumnId, columnIds);
    }

    private LambdaQueryWrapper<SuperAgentKgEvidence> evidenceIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getId, sourceId)
            .eq(SuperAgentKgEvidence::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEvidence::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentKgEntity> entityIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getId, sourceId)
            .eq(SuperAgentKgEntity::getDocumentId, context.document().getId())
            .eq(SuperAgentKgEntity::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentKgCommunity> communityIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getId, sourceId)
            .eq(SuperAgentKgCommunity::getDocumentId, context.document().getId())
            .eq(SuperAgentKgCommunity::getTaskId, context.indexTaskId())
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode());
    }

    private LambdaQueryWrapper<SuperAgentRaptorNode> raptorIdentityWrapper(ArtifactContext context, Long sourceId) {
        return new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getId, sourceId)
            .eq(SuperAgentRaptorNode::getDocumentId, context.document().getId())
            .eq(SuperAgentRaptorNode::getTaskId, context.indexTaskId())
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode());
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem nodeFromSource(DocumentRagArtifactType type, Object source) {
        return switch (type) {
            case DOCUMENT -> documentNode((SuperAgentDocument) source);
            case STRUCTURE_NODE -> structureNode((SuperAgentDocumentStructureNode) source);
            case PARSE_BLOCK -> blockNode((SuperAgentDocumentBlock) source);
            case PARENT_BLOCK -> parentNode((SuperAgentDocumentParentBlock) source);
            case TABLE -> tableNode((SuperAgentDocumentTable) source);
            case CHILD_CHUNK -> chunkNode((SuperAgentDocumentChunk) source);
            case KG_ENTITY -> entityNode((SuperAgentKgEntity) source);
            case KG_COMMUNITY -> communityNode((SuperAgentKgCommunity) source);
            case KG_EVIDENCE -> evidenceNode((SuperAgentKgEvidence) source);
            case RAPTOR_NODE -> raptorNode((SuperAgentRaptorNode) source);
        };
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem documentNode(SuperAgentDocument document) {
        return node(
            DocumentRagArtifactType.DOCUMENT,
            document.getId(),
            null,
            "原始文档",
            StrUtil.blankToDefault(document.getDocumentName(), "文档根节点"),
            "",
            "",
            null,
            "",
            "",
            "success"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem blockNode(SuperAgentDocumentBlock block) {
        return blockNode(block, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem structureNode(SuperAgentDocumentStructureNode structure) {
        return structureNode(structure, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem structureNode(SuperAgentDocumentStructureNode structure,
                                                                       String content) {
        return node(
            DocumentRagArtifactType.STRUCTURE_NODE,
            structure.getId(),
            structure.getNodeNo(),
            firstNotBlank(structure.getTitle(), structure.getNodeCode(), "结构节点 #" + valueOrDash(structure.getNodeNo())),
            firstNotBlank(structure.getSyntaxNodeType(), "第 " + valueOrDash(structure.getDepth()) + " 层"),
            firstNotBlank(structure.getSectionPath(), structure.getCanonicalPath()),
            "",
            null,
            "",
            preview(content),
            "neutral"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem blockNode(SuperAgentDocumentBlock block, String content) {
        return node(
            DocumentRagArtifactType.PARSE_BLOCK,
            block.getId(),
            block.getBlockNo(),
            "解析块 #" + valueOrDash(block.getBlockNo()),
            StrUtil.blankToDefault(block.getBlockType(), "解析块"),
            block.getSectionPath(),
            block.getPageRange(),
            block.getPageNo(),
            StrUtil.isBlank(block.getBboxJson()) ? "" : "block-" + block.getId(),
            preview(content),
            "neutral"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem parentNode(SuperAgentDocumentParentBlock parent) {
        return parentNode(parent, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem parentNode(SuperAgentDocumentParentBlock parent, String content) {
        return node(
            DocumentRagArtifactType.PARENT_BLOCK,
            parent.getId(),
            parent.getParentNo(),
            "父级块 #" + valueOrDash(parent.getParentNo()),
            parent.getChildCount() == null ? "回答上下文" : parent.getChildCount() + " 个子块",
            parent.getSectionPath(),
            parent.getPageRange(),
            null,
            "",
            preview(content),
            "success"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem chunkNode(SuperAgentDocumentChunk chunk) {
        return chunkNode(chunk, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem chunkNode(SuperAgentDocumentChunk chunk, String content) {
        return node(
            DocumentRagArtifactType.CHILD_CHUNK,
            chunk.getId(),
            chunk.getChunkNo(),
            "检索子块 #" + valueOrDash(chunk.getChunkNo()),
            firstNotBlank(chunk.getChunkType(), chunk.getTitle(), "召回单元"),
            chunk.getSectionPath(),
            chunk.getPageRange(),
            chunk.getPageNo(),
            "",
            preview(content),
            Objects.equals(chunk.getVectorStatus(), DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode()) ? "success" : "warning"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem tableNode(SuperAgentDocumentTable table) {
        return tableNode(table, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem tableNode(SuperAgentDocumentTable table, String content) {
        return node(
            DocumentRagArtifactType.TABLE,
            table.getId(),
            table.getTableNo(),
            "表格 #" + valueOrDash(table.getTableNo()),
            firstNotBlank(table.getTitle(), tableSize(table), "结构化表格"),
            table.getSectionPath(),
            table.getPageRange(),
            table.getPageNo(),
            StrUtil.isBlank(table.getBboxJson()) ? "" : "table-" + table.getId(),
            preview(content),
            "success"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem evidenceNode(SuperAgentKgEvidence evidence) {
        return evidenceNode(evidence, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem entityNode(SuperAgentKgEntity entity) {
        return entityNode(entity, entity.getDescription());
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem entityNode(SuperAgentKgEntity entity, String content) {
        return node(
            DocumentRagArtifactType.KG_ENTITY,
            entity.getId(),
            null,
            firstNotBlank(entity.getName(), entity.getNormalizedName(), "实体 #" + valueOrDash(entity.getId())),
            StrUtil.blankToDefault(entity.getEntityType(), "未分类实体"),
            "",
            "",
            null,
            "",
            preview(content),
            "neutral"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem communityNode(SuperAgentKgCommunity community) {
        return communityNode(community, community.getSummary());
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem communityNode(SuperAgentKgCommunity community,
                                                                        String content) {
        String subtitle = readLongList(community.getEntityIdsJson()).size() + " 个实体 · "
            + readLongList(community.getRelationIdsJson()).size() + " 条关系";
        return node(
            DocumentRagArtifactType.KG_COMMUNITY,
            community.getId(),
            community.getCommunityNo(),
            firstNotBlank(community.getTitle(), "社区 #" + valueOrDash(community.getCommunityNo())),
            subtitle,
            "",
            "",
            null,
            "",
            preview(content),
            "neutral"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem evidenceNode(SuperAgentKgEvidence evidence, String content) {
        return node(
            DocumentRagArtifactType.KG_EVIDENCE,
            evidence.getId(),
            null,
            "图谱证据 #" + valueOrDash(evidence.getId()),
            evidence.getEntityId() != null ? "实体证据" : "关系证据",
            evidence.getSectionPath(),
            evidence.getPageRange(),
            evidence.getPageNo(),
            "",
            preview(content),
            evidence.getChunkId() != null ? "success" : "warning"
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem raptorNode(SuperAgentRaptorNode raptor) {
        return raptorNode(raptor, "");
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem raptorNode(SuperAgentRaptorNode raptor, String content) {
        DocumentRagSnapshotVo.ArtifactGraphNodeItem item = node(
            DocumentRagArtifactType.RAPTOR_NODE,
            raptor.getId(),
            raptor.getNodeNo(),
            firstNotBlank(raptor.getTitle(), "层级摘要 #" + valueOrDash(raptor.getNodeNo())),
            "第 " + valueOrDash(raptor.getNodeLevel()) + " 层",
            raptor.getSectionPath(),
            raptor.getPageRange(),
            null,
            "",
            preview(content),
            "neutral"
        );
        item.setChildCount(readLongList(raptor.getChildNodeIdsJson()).size());
        return item;
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem raptorDetailNode(SuperAgentRaptorNode raptor) {
        return raptorNode(raptor, raptor.getSummary());
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem parentDetailNode(SuperAgentDocumentParentBlock parent) {
        DocumentRagSnapshotVo.ArtifactGraphNodeItem item = parentNode(parent, parent.getParentText());
        item.setLabel(firstNotBlank(parent.getSectionPath(), item.getLabel()));
        return item;
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem chunkDetailNode(SuperAgentDocumentChunk chunk) {
        DocumentRagSnapshotVo.ArtifactGraphNodeItem item = chunkNode(chunk, chunk.getChunkText());
        item.setLabel(firstNotBlank(chunk.getTitle(), chunk.getSectionPath(), item.getLabel()));
        return item;
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem node(DocumentRagArtifactType type,
                                                              Long sourceId,
                                                              Integer sourceNo,
                                                              String label,
                                                              String subtitle,
                                                              String sectionPath,
                                                              String pageRange,
                                                              Integer pageNo,
                                                              String overlayId,
                                                              String textPreview,
                                                              String tone) {
        return new DocumentRagSnapshotVo.ArtifactGraphNodeItem(
            type.nodeId(sourceId),
            type.getCode(),
            sourceId,
            sourceNo,
            StrUtil.blankToDefault(label, type.nodeId(sourceId)),
            StrUtil.blankToDefault(subtitle, ""),
            StrUtil.blankToDefault(sectionPath, ""),
            StrUtil.blankToDefault(pageRange, ""),
            pageNo,
            StrUtil.blankToDefault(overlayId, ""),
            StrUtil.blankToDefault(textPreview, ""),
            StrUtil.blankToDefault(tone, "neutral"),
            null
        );
    }

    private DocumentRagArtifactNodeDetailVo detailVo(ArtifactContext context,
                                                      DocumentRagSnapshotVo.ArtifactGraphNodeItem node,
                                                      String content,
                                                      List<DocumentRagArtifactNodeDetailVo.AttributeItem> attributes) {
        return detailVo(context, node, content, attributes, null);
    }

    private DocumentRagArtifactNodeDetailVo detailVo(ArtifactContext context,
                                                      DocumentRagSnapshotVo.ArtifactGraphNodeItem node,
                                                      String content,
                                                      List<DocumentRagArtifactNodeDetailVo.AttributeItem> attributes,
                                                      DocumentRagArtifactNodeDetailVo.PresentationItem presentation) {
        return new DocumentRagArtifactNodeDetailVo(
            context.document().getId(),
            context.parseTaskId(),
            context.indexTaskId(),
            node,
            StrUtil.blankToDefault(content, ""),
            attributes,
            presentation
        );
    }

    private List<DocumentRagArtifactNodeDetailVo.AttributeItem> attributes(
        DocumentRagArtifactNodeDetailVo.AttributeItem... items) {
        List<DocumentRagArtifactNodeDetailVo.AttributeItem> result = new ArrayList<>();
        for (DocumentRagArtifactNodeDetailVo.AttributeItem item : items) {
            if (item != null && StrUtil.isNotBlank(item.getValue())) {
                result.add(item);
            }
        }
        return result;
    }

    private DocumentRagArtifactNodeDetailVo.AttributeItem attribute(String label, Object value) {
        return new DocumentRagArtifactNodeDetailVo.AttributeItem(label, value(value));
    }

    private <T> NodePage nodePage(IPage<T> page, Function<T, DocumentRagSnapshotVo.ArtifactGraphNodeItem> mapper) {
        return new NodePage(page.getTotal(), page.getRecords().stream().map(mapper).toList());
    }

    private boolean matchesDocument(SuperAgentDocument document, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        String normalized = keyword.toLowerCase();
        return value(document.getId()).contains(normalized)
            || value(document.getDocumentName()).toLowerCase().contains(normalized)
            || value(document.getOriginalFileName()).toLowerCase().contains(normalized);
    }

    private DocumentRagArtifactType requireNodeType(String code) {
        DocumentRagArtifactType type = DocumentRagArtifactType.fromCode(code);
        if (type == null) {
            throw invalidRequest("不支持的 RAG 产物类型：" + StrUtil.blankToDefault(code, "-"));
        }
        return type;
    }

    private NodeIdentity requireNodeIdentity(String nodeId) {
        String normalized = StrUtil.trim(nodeId);
        DocumentRagArtifactType type = DocumentRagArtifactType.fromNodeId(normalized);
        Long sourceId = type == null ? null : type.sourceId(normalized);
        if (type == null || sourceId == null) {
            throw invalidRequest("RAG 产物节点 ID 不合法。");
        }
        return new NodeIdentity(type, sourceId);
    }

    private RelationDirection requireDirection(String direction) {
        try {
            return RelationDirection.valueOf(StrUtil.blankToDefault(direction, "").trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            throw invalidRequest("关系方向仅支持 UPSTREAM、DOWNSTREAM、INCOMING 或 OUTGOING。");
        }
    }

    private SuperAgentFrameException invalidRequest(String message) {
        return new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), message);
    }

    private SuperAgentFrameException nodeNotFound() {
        return new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "RAG 产物节点不存在。");
    }

    private Long numericKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(keyword);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST_TYPE).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> readStringList(String json, int limit) {
        if (StrUtil.isBlank(json) || limit <= 0) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE).stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String raptorScopeLabel(String scopeType) {
        if (StrUtil.isBlank(scopeType)) {
            return "当前文档";
        }
        return switch (scopeType.trim().toUpperCase()) {
            case "DOCUMENT" -> "当前文档";
            case "SECTION" -> "当前章节";
            case "PARENT_BLOCK" -> "回答上下文";
            case "CHUNK" -> "来源片段";
            default -> "文档范围";
        };
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private String limitClause(long offset, int limit) {
        return "limit " + Math.max(0L, offset) + ", " + Math.max(1, limit);
    }

    private String preview(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= PREVIEW_LENGTH ? normalized : normalized.substring(0, PREVIEW_LENGTH) + "...";
    }

    private String rangeText(Integer start, Integer end) {
        if (start == null && end == null) {
            return "";
        }
        if (Objects.equals(start, end) || end == null) {
            return value(start);
        }
        return value(start) + " - " + value(end);
    }

    private String tableSize(SuperAgentDocumentTable table) {
        if (table.getRowCount() == null && table.getColumnCount() == null) {
            return "";
        }
        return valueOrDash(table.getRowCount()) + " 行 x " + valueOrDash(table.getColumnCount()) + " 列";
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String valueOrDash(Object value) {
        return value == null || StrUtil.isBlank(String.valueOf(value)) ? "-" : String.valueOf(value);
    }

    private record ArtifactContext(SuperAgentDocument document, Long parseTaskId, Long indexTaskId) {
    }

    private record NodePage(long total, List<DocumentRagSnapshotVo.ArtifactGraphNodeItem> records) {

        private static NodePage empty() {
            return new NodePage(0L, List.of());
        }
    }

    private record NodeIdentity(DocumentRagArtifactType type, Long sourceId) {
    }

    private record ArtifactFocus(NodeIdentity identity,
                                 Object source,
                                 DocumentRagSnapshotVo.ArtifactGraphNodeItem node) {
    }

    private record RelationSegment(long total, RelationLoader loader) {
    }

    private enum RelationDirection {
        UPSTREAM,
        DOWNSTREAM,
        INCOMING,
        OUTGOING
    }

    @FunctionalInterface
    private interface RelationLoader {

        List<DocumentRagArtifactRelationPageVo.RelationItem> load(long offset, int limit);
    }

    @FunctionalInterface
    private interface RelationSupplier {

        DocumentRagArtifactRelationPageVo.RelationItem get();
    }
}
