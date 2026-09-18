-- S31 删除文档级社区表
-- 背景：Python 抽取契约（unified candidates）永不产出文档级 communities，
-- smartledge_kg_community 恒为空表，Java 侧生产代码路径已全部删除
-- （GraphRAG 构建不再写社区、检索不再读社区、快照/产物/质量统计不再展示社区）。
-- 注意：跨文档社区（smartledge_kg_cross_document_community 一族）不受本迁移影响，
-- 检索运行时仍正常消费跨文档社区结果与 KG_COMMUNITY_* 元数据键。
DROP TABLE IF EXISTS `smartledge_kg_community`;
