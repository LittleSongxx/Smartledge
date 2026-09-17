package org.smartledge.ai.manage.service;

import org.smartledge.database.tenant.RequestIdentity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 文档级可见性与写权限的唯一解析点（ACL）。
 *
 * <p>职责边界：只回答"这个身份对哪些文档有哪种权限"，不回答"文档是否可检索"（那是
 * 知识库元数据过滤与索引状态的事）。调用方把两者**取交集**，本接口只做收窄，不做放宽。</p>
 *
 * <h2>权限等级</h2>
 *
 * <p>等级是单调的：{@code MANAGE ⇒ WRITE ⇒ READ}。因此"可管理"必然"可写"、"可写"必然"可读"，
 * 不会出现"能删但不能看"这种自相矛盾的授权组合。</p>
 *
 * <h2>fail closed 约定</h2>
 *
 * <p>身份缺失、ACL 读取失败、租户上下文缺失，一律返回**空集合**：宁可让用户看不到，
 * 也不能因为一次异常就把全部文档放行。实现不得把异常吞成"全部可见"。</p>
 */
public interface DocumentAclStore {

    /**
     * 给定候选文档，返回该身份可读的子集（READ/WRITE/MANAGE 任一即算可读）。
     *
     * @param documentIds 候选文档 id（通常是知识库元数据过滤后的结果）
     * @param identity    当前身份；{@code null} 表示未认证，返回空集合
     */
    Set<Long> visibleDocumentIds(Collection<Long> documentIds, RequestIdentity identity);

    /**
     * 当前身份在本租户内可读的全部文档 id。
     *
     * <p>给管理端列表用：没有 {@code document:read-all} 时只能看见 ACL 授权过的文档。
     * 失败同样 fail closed，返回空集合。</p>
     */
    Set<Long> visibleDocumentIdsForIdentity(RequestIdentity identity);

    /** 给定候选文档，返回该身份可写的子集（WRITE 或 MANAGE）。 */
    Set<Long> writableDocumentIds(Collection<Long> documentIds, RequestIdentity identity);

    /** 给定候选文档，返回该身份可管理的子集（仅 MANAGE）。 */
    Set<Long> manageableDocumentIds(Collection<Long> documentIds, RequestIdentity identity);

    /**
     * 列出某份文档的授权行（含已停用行，供界面显示"已撤销"）。
     *
     * <p>只读授权元数据，因此调用方必须先证明自己对该文档有 MANAGE —— 本方法不做这个判定，
     * 它只负责"取出来"，业务侧的资格判定属于调用方的责任（唯一入口见
     * {@code DocumentAclManageService}）。</p>
     */
    List<DocumentAclRecord> listByDocument(Long documentId, RequestIdentity identity);

    /**
     * 记录文档授权（上传时给上传者 MANAGE，使创建者始终可见自己上传的文档）。
     *
     * <p><b>唯一写入口</b>，语义是 upsert：同一文档 + 同一主体重复授予是**改权限**
     * （`uk_document_acl(document_id, principal_type, principal_id)` 决定了不可能有第二行），
     * 已停用的行会被重新启用。这样"授予"和"撤销后重新授予"走同一条路径，
     * 不存在"先删后插"这种会产生中间态的第二条写路径。</p>
     *
     * @param grantedBy 授权人用户 id，可为空（系统授予）
     */
    void grant(Long documentId,
               RequestIdentity identity,
               String principalType,
               Long principalId,
               String permission,
               Long grantedBy);

    /**
     * 撤销文档授权（软删：写 {@code status=0}，保留行以便审计与重新授予）。
     *
     * @return 是否确实改变了某一行的状态（false 表示该主体本来就没有有效授权）
     */
    boolean revoke(Long documentId, String principalType, Long principalId, RequestIdentity identity);

    /**
     * 删除文档时撤销该文档全部 ACL（软删）。
     *
     * @return 被停用的有效授权行数
     */
    int revokeAllForDocument(Long documentId, RequestIdentity identity);

    /**
     * 一条授权记录（授权行本身，不含主体显示名 —— 名字由上层按主体类型解析）。
     *
     * @param principalType {@code USER} / {@code ROLE}
     * @param permission    {@code READ} / {@code WRITE} / {@code MANAGE}
     * @param enabled       该行是否生效（false = 曾授予、已撤销）
     */
    record DocumentAclRecord(Long id,
                             String principalType,
                             Long principalId,
                             String permission,
                             Long grantedBy,
                             boolean enabled) {
    }
}
