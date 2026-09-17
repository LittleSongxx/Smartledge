package org.smartledge.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import org.smartledge.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@AllArgsConstructor
public class DocumentStructureProjector {

    private static final Pattern DECIMAL_CODE = Pattern.compile("^(\\d+(?:\\.\\d+)+)(?=\\s|[\u3001：:]|$)");
    private static final Pattern CHINESE_OUTLINE_CODE = Pattern.compile("^([\u96f6〇一二三四五六七八九十百两]+[\u3001.])");
    private static final Pattern CHAPTER_CODE = Pattern.compile("^(第([\u96f6〇一二三四五六七八九十百两\\d]+)[章节条部分])");
    private static final Pattern APPENDIX_CODE = Pattern.compile("^(附录\\s*([A-Za-z\u96f6〇一二三四五六七八九十百两\\d]+))");

    private final DocumentMarkdownSyntaxContract syntaxContract;

    public List<DocumentStructureNodeCandidate> projectMarkdown(String documentTitle,
                                                                DocumentMarkdownSyntaxCandidate syntax) {
        syntaxContract.validate(syntax);
        Map<String, DocumentMarkdownSyntaxNodeCandidate> syntaxById = indexSyntax(syntax.getNodes());
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent = indexChildren(syntax.getNodes());
        List<Projection> projections = new ArrayList<>();
        Map<String, Projection> projectionBySyntaxId = new LinkedHashMap<>();
        Map<String, Projection> headingByNumericPath = new LinkedHashMap<>();
        Map<String, Integer> canonicalOccurrences = new LinkedHashMap<>();
        Deque<HeadingProjection> headingStack = new ArrayDeque<>();

        DocumentMarkdownSyntaxNodeCandidate syntaxRoot = syntax.getNodes().get(0);
        Projection root = new Projection(rootCandidate(documentTitle, provenance(syntax, syntaxRoot)), null);
        projections.add(root);
        projectionBySyntaxId.put(syntaxRoot.getNodeId(), root);
        Projection currentSection = root;

        for (int index = 1; index < syntax.getNodes().size(); index++) {
            DocumentMarkdownSyntaxNodeCandidate node = syntax.getNodes().get(index);
            if ("HEADING".equals(node.getNodeType())) {
                if (isDocumentTitleHeading(documentTitle, node)) {
                    headingStack.clear();
                    currentSection = root;
                    continue;
                }
                while (!headingStack.isEmpty() && headingStack.peekLast().level() >= node.getLevel()) {
                    headingStack.removeLast();
                }
                HeadingCode headingCode = headingCode(node.getText());
                Projection levelParent = headingStack.isEmpty() ? root : headingStack.peekLast().projection();
                Projection numericParent = numericParent(headingCode.numericPath(), headingByNumericPath);
                Projection parent = numericParent == null ? levelParent : numericParent;
                Projection heading = new Projection(
                    sectionCandidate(node, syntax, parent, headingCode, projections.size() + 1, canonicalOccurrences),
                    node.getNodeId()
                );
                addProjection(projections, projectionBySyntaxId, heading);
                headingStack.addLast(new HeadingProjection(node.getLevel(), heading));
                if (!headingCode.numericPath().isEmpty()) {
                    headingByNumericPath.put(numericKey(headingCode.numericPath()), heading);
                }
                currentSection = heading;
                continue;
            }
            if (!"LIST_ITEM".equals(node.getNodeType())) {
                continue;
            }
            Projection parent = projectedListAncestor(node, syntaxById, projectionBySyntaxId);
            if (parent == null) {
                parent = currentSection;
            }
            Projection listItem = new Projection(
                listItemCandidate(node, syntax, parent, childrenByParent, projections.size() + 1, canonicalOccurrences),
                node.getNodeId()
            );
            addProjection(projections, projectionBySyntaxId, listItem);
        }

        List<DocumentStructureNodeCandidate> candidates = projections.stream().map(Projection::candidate).toList();
        linkSiblings(candidates);
        validateProjection(candidates);
        return List.copyOf(candidates);
    }

    public List<DocumentStructureNodeCandidate> replayMarkdown(String documentTitle, byte[] artifactBytes) {
        return projectMarkdown(documentTitle, syntaxContract.read(artifactBytes));
    }

    public List<DocumentStructureNodeCandidate> projectProviderBlocks(String documentTitle,
                                                                      List<DocumentBlockCandidate> blocks) {
        List<DocumentStructureNodeCandidate> candidates = new ArrayList<>();
        DocumentStructureNodeCandidate root = rootCandidate(documentTitle, null);
        root.setCanonicalPath("/");
        candidates.add(root);
        int sectionIndex = 0;
        for (DocumentBlockCandidate block : blocks == null ? List.<DocumentBlockCandidate>of() : blocks) {
            if (block == null || !"TITLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), ""))
                || StrUtil.isBlank(block.getText())) {
                continue;
            }
            sectionIndex++;
            DocumentStructureNodeCandidate section = new DocumentStructureNodeCandidate();
            section.setNodeNo(candidates.size() + 1);
            section.setNodeType(DocumentStructureNodeTypeEnum.SECTION.getCode());
            section.setParentNodeNo(1);
            section.setDepth(1);
            section.setNodeCode(String.valueOf(sectionIndex));
            section.setTitle(block.getText().trim());
            section.setAnchorText(limit(block.getText().trim(), 200));
            section.setCanonicalPath(StrUtil.blankToDefault(block.getCanonicalPath(), "/provider/" + sectionIndex));
            section.setSectionPath(StrUtil.blankToDefault(block.getSectionPath(), block.getText()).trim());
            section.setContentText(block.getText().trim());
            candidates.add(section);
        }
        linkSiblings(candidates);
        validateProjection(candidates);
        return List.copyOf(candidates);
    }

    private DocumentStructureNodeCandidate rootCandidate(String documentTitle,
                                                         DocumentStructureSyntaxProvenanceCandidate provenance) {
        DocumentStructureNodeCandidate root = new DocumentStructureNodeCandidate();
        root.setNodeNo(1);
        root.setNodeType(DocumentStructureNodeTypeEnum.DOCUMENT.getCode());
        root.setDepth(0);
        root.setNodeCode("ROOT");
        root.setTitle(StrUtil.blankToDefault(documentTitle, "document"));
        root.setAnchorText(StrUtil.blankToDefault(documentTitle, "document"));
        root.setCanonicalPath("/document");
        root.setSectionPath("");
        root.setContentText(StrUtil.blankToDefault(documentTitle, "document"));
        root.setSyntaxProvenance(provenance);
        return root;
    }

    private DocumentStructureNodeCandidate sectionCandidate(DocumentMarkdownSyntaxNodeCandidate node,
                                                            DocumentMarkdownSyntaxCandidate syntax,
                                                            Projection parent,
                                                            HeadingCode headingCode,
                                                            int nodeNo,
                                                            Map<String, Integer> canonicalOccurrences) {
        String title = node.getText().trim();
        DocumentStructureNodeCandidate candidate = new DocumentStructureNodeCandidate();
        candidate.setNodeNo(nodeNo);
        candidate.setNodeType(DocumentStructureNodeTypeEnum.SECTION.getCode());
        candidate.setParentNodeNo(parent.candidate().getNodeNo());
        candidate.setDepth(parent.candidate().getDepth() + 1);
        candidate.setNodeCode(headingCode.rawCode());
        candidate.setTitle(title);
        candidate.setAnchorText(limit(title, 200));
        candidate.setSectionPath(joinSectionPath(parent.candidate().getSectionPath(), title));
        candidate.setCanonicalPath(canonicalPath(parent.candidate().getCanonicalPath(),
            StrUtil.blankToDefault(headingCode.rawCode(), title), candidate.getNodeNo(), canonicalOccurrences));
        candidate.setContentText(title);
        candidate.setSyntaxProvenance(provenance(syntax, node));
        return candidate;
    }

    private DocumentStructureNodeCandidate listItemCandidate(DocumentMarkdownSyntaxNodeCandidate node,
                                                             DocumentMarkdownSyntaxCandidate syntax,
                                                             Projection parent,
                                                             Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent,
                                                             int nodeNo,
                                                             Map<String, Integer> canonicalOccurrences) {
        String text = listItemText(node, childrenByParent);
        DocumentStructureNodeCandidate candidate = new DocumentStructureNodeCandidate();
        candidate.setNodeNo(nodeNo);
        candidate.setNodeType(DocumentStructureNodeTypeEnum.LIST_ITEM.getCode());
        candidate.setParentNodeNo(parent.candidate().getNodeNo());
        candidate.setDepth(parent.candidate().getDepth() + 1);
        candidate.setNodeCode(node.getMarker());
        candidate.setTitle(text);
        candidate.setAnchorText(limit(text, 200));
        candidate.setSectionPath(parent.candidate().getSectionPath());
        String segment = node.getOrdinal() == null ? "item-" + node.getOrder() : "item-" + node.getOrdinal();
        candidate.setCanonicalPath(canonicalPath(parent.candidate().getCanonicalPath(), segment,
            candidate.getNodeNo(), canonicalOccurrences));
        candidate.setContentText(text);
        candidate.setItemIndex(node.getOrdinal());
        candidate.setSyntaxProvenance(provenance(syntax, node));
        return candidate;
    }

    private void addProjection(List<Projection> projections,
                               Map<String, Projection> projectionBySyntaxId,
                               Projection projection) {
        String syntaxNodeId = projection.syntaxNodeId();
        if (StrUtil.isBlank(syntaxNodeId) || projectionBySyntaxId.putIfAbsent(syntaxNodeId, projection) != null) {
            throw new IllegalStateException("Markdown syntax node 被重复投影: " + syntaxNodeId);
        }
        projections.add(projection);
    }

    private Map<String, DocumentMarkdownSyntaxNodeCandidate> indexSyntax(List<DocumentMarkdownSyntaxNodeCandidate> nodes) {
        Map<String, DocumentMarkdownSyntaxNodeCandidate> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.getNodeId(), node));
        return result;
    }

    private Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> indexChildren(List<DocumentMarkdownSyntaxNodeCandidate> nodes) {
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> result = new LinkedHashMap<>();
        for (DocumentMarkdownSyntaxNodeCandidate node : nodes) {
            if (node.getParentNodeId() != null) {
                result.computeIfAbsent(node.getParentNodeId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        return result;
    }

    private Projection projectedListAncestor(DocumentMarkdownSyntaxNodeCandidate node,
                                             Map<String, DocumentMarkdownSyntaxNodeCandidate> syntaxById,
                                             Map<String, Projection> projectionBySyntaxId) {
        String parentId = node.getParentNodeId();
        while (parentId != null) {
            Projection projection = projectionBySyntaxId.get(parentId);
            if (projection != null
                && DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(projection.candidate().getNodeType())) {
                return projection;
            }
            DocumentMarkdownSyntaxNodeCandidate parent = syntaxById.get(parentId);
            parentId = parent == null ? null : parent.getParentNodeId();
        }
        return null;
    }

    private Projection numericParent(List<Integer> numericPath, Map<String, Projection> headingByNumericPath) {
        if (numericPath.size() <= 1) {
            return null;
        }
        return headingByNumericPath.get(numericKey(numericPath.subList(0, numericPath.size() - 1)));
    }

    private String listItemText(DocumentMarkdownSyntaxNodeCandidate node,
                                Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent) {
        return childrenByParent.getOrDefault(node.getNodeId(), List.of()).stream()
            .filter(child -> "PARAGRAPH".equals(child.getNodeType()))
            .map(DocumentMarkdownSyntaxNodeCandidate::getText)
            .filter(StrUtil::isNotBlank)
            .findFirst()
            .orElse(StrUtil.blankToDefault(node.getText(), ""))
            .trim();
    }

    private DocumentStructureSyntaxProvenanceCandidate provenance(DocumentMarkdownSyntaxCandidate syntax,
                                                                  DocumentMarkdownSyntaxNodeCandidate node) {
        DocumentMarkdownSourceSpanCandidate span = node.getSourceSpan();
        return new DocumentStructureSyntaxProvenanceCandidate(
            syntax.getSchemaVersion(),
            syntax.getSourceSha256(),
            node.getNodeId(),
            node.getNodeType(),
            node.getOrigin(),
            new DocumentMarkdownSourceSpanCandidate(
                span.getStartByte(),
                span.getEndByte(),
                span.getStartLine(),
                span.getStartColumn(),
                span.getEndLine(),
                span.getEndColumn()
            )
        );
    }

    private void linkSiblings(List<DocumentStructureNodeCandidate> candidates) {
        Map<Integer, List<DocumentStructureNodeCandidate>> childrenByParent = new LinkedHashMap<>();
        for (DocumentStructureNodeCandidate candidate : candidates) {
            if (candidate.getParentNodeNo() != null) {
                childrenByParent.computeIfAbsent(candidate.getParentNodeNo(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        for (List<DocumentStructureNodeCandidate> siblings : childrenByParent.values()) {
            for (int index = 0; index < siblings.size(); index++) {
                siblings.get(index).setPrevSiblingNodeNo(index == 0 ? null : siblings.get(index - 1).getNodeNo());
                siblings.get(index).setNextSiblingNodeNo(index == siblings.size() - 1 ? null : siblings.get(index + 1).getNodeNo());
            }
        }
    }

    private void validateProjection(List<DocumentStructureNodeCandidate> candidates) {
        if (candidates.isEmpty()
            || !DocumentStructureNodeTypeEnum.DOCUMENT.getCode().equals(candidates.get(0).getNodeType())) {
            throw new IllegalStateException("结构投影缺少 DOCUMENT root");
        }
        Map<Integer, DocumentStructureNodeCandidate> byNodeNo = new LinkedHashMap<>();
        Set<String> syntaxNodeIds = new LinkedHashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            DocumentStructureNodeCandidate candidate = candidates.get(index);
            if (candidate.getNodeNo() == null || candidate.getNodeNo() != index + 1) {
                throw new IllegalStateException("结构投影 nodeNo 不连续");
            }
            if (index == 0) {
                if (candidate.getParentNodeNo() != null || candidate.getDepth() == null || candidate.getDepth() != 0) {
                    throw new IllegalStateException("结构投影 root 非法");
                }
            }
            else {
                DocumentStructureNodeCandidate parent = byNodeNo.get(candidate.getParentNodeNo());
                if (parent == null || candidate.getDepth() == null || candidate.getDepth() != parent.getDepth() + 1) {
                    throw new IllegalStateException("结构投影 parent/depth 不守恒: " + candidate.getNodeNo());
                }
            }
            if (candidate.getSyntaxProvenance() != null
                && !syntaxNodeIds.add(candidate.getSyntaxProvenance().getSyntaxNodeId())) {
                throw new IllegalStateException("结构投影 syntax node provenance 重复: "
                    + candidate.getSyntaxProvenance().getSyntaxNodeId());
            }
            byNodeNo.put(candidate.getNodeNo(), candidate);
        }
    }

    private boolean isDocumentTitleHeading(String documentTitle, DocumentMarkdownSyntaxNodeCandidate node) {
        if (node.getLevel() == null || node.getLevel() != 1) {
            return false;
        }
        String baseName = StrUtil.blankToDefault(documentTitle, "document");
        int extension = baseName.lastIndexOf('.');
        if (extension > 0) {
            baseName = baseName.substring(0, extension);
        }
        return normalizeComparable(baseName).equals(normalizeComparable(node.getText()));
    }

    private HeadingCode headingCode(String title) {
        String value = StrUtil.blankToDefault(title, "").trim();
        Matcher decimal = DECIMAL_CODE.matcher(value);
        if (decimal.find()) {
            return new HeadingCode(decimal.group(1), decimalPath(decimal.group(1)));
        }
        Matcher chapter = CHAPTER_CODE.matcher(value);
        if (chapter.find()) {
            Integer number = parseNumber(chapter.group(2));
            return new HeadingCode(chapter.group(1), number == null ? List.of() : List.of(number));
        }
        Matcher chineseOutline = CHINESE_OUTLINE_CODE.matcher(value);
        if (chineseOutline.find()) {
            String code = chineseOutline.group(1);
            Integer number = parseNumber(code.substring(0, code.length() - 1));
            return new HeadingCode(code, number == null ? List.of() : List.of(number));
        }
        Matcher appendix = APPENDIX_CODE.matcher(value);
        if (appendix.find()) {
            return new HeadingCode(appendix.group(1), List.of());
        }
        return new HeadingCode("", List.of());
    }

    private List<Integer> decimalPath(String code) {
        List<Integer> result = new ArrayList<>();
        for (String segment : code.split("\\.")) {
            result.add(Integer.parseInt(segment));
        }
        return List.copyOf(result);
    }

    private Integer parseNumber(String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digits = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
            Map.entry('八', 8), Map.entry('九', 9)
        );
        int total = 0;
        int current = 0;
        for (char character : normalized.toCharArray()) {
            if (digits.containsKey(character)) {
                current = digits.get(character);
            }
            else if (character == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            }
            else if (character == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            }
            else {
                return null;
            }
        }
        return total + current;
    }

    private String numericKey(List<Integer> path) {
        return path.stream().map(String::valueOf).reduce((left, right) -> left + "." + right).orElse("");
    }

    private String joinSectionPath(String parentPath, String title) {
        return StrUtil.isBlank(parentPath) ? title : parentPath + " > " + title;
    }

    private String canonicalPath(String parentPath,
                                 String value,
                                 int nodeNo,
                                 Map<String, Integer> occurrences) {
        String basePath = StrUtil.blankToDefault(parentPath, "/document");
        String segment = slug(value);
        String key = basePath + "/" + segment;
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        return key + (occurrence == 1 ? "" : "-" + nodeNo);
    }

    private String slug(String value) {
        String slug = StrUtil.blankToDefault(value, "node")
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("[^\\p{IsHan}A-Za-z0-9_.-]", "");
        return slug.isBlank() ? "node" : slug;
    }

    private String normalizeComparable(String value) {
        return StrUtil.blankToDefault(value, "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private String limit(String value, int maxLength) {
        String normalized = StrUtil.blankToDefault(value, "");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record Projection(DocumentStructureNodeCandidate candidate, String syntaxNodeId) {
    }

    private record HeadingProjection(int level, Projection projection) {
    }

    private record HeadingCode(String rawCode, List<Integer> numericPath) {
    }

}
