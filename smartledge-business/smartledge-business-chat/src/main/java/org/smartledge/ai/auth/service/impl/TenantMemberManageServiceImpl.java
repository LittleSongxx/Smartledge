package org.smartledge.ai.auth.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.smartledge.ai.auth.data.AuthPermission;
import org.smartledge.ai.auth.data.AuthRole;
import org.smartledge.ai.auth.data.AuthRolePermission;
import org.smartledge.ai.auth.data.AuthUserAccount;
import org.smartledge.ai.auth.data.AuthUserRole;
import org.smartledge.ai.auth.dto.TenantMemberPageQueryDto;
import org.smartledge.ai.auth.dto.TenantMemberSaveDto;
import org.smartledge.ai.auth.dto.TenantMemberStatusUpdateDto;
import org.smartledge.ai.auth.mapper.AuthPermissionMapper;
import org.smartledge.ai.auth.mapper.AuthRoleMapper;
import org.smartledge.ai.auth.mapper.AuthRolePermissionMapper;
import org.smartledge.ai.auth.mapper.AuthUserAccountMapper;
import org.smartledge.ai.auth.mapper.AuthUserRoleMapper;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.service.TenantMemberManageService;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.auth.support.PasswordVerifier;
import org.smartledge.ai.auth.vo.TenantMemberItemVo;
import org.smartledge.ai.auth.vo.TenantMemberPageQueryVo;
import org.smartledge.ai.auth.vo.TenantRoleItemVo;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 租户内成员与角色管理的实现（S23-B2）。
 *
 * <p>三条刻意的约束：</p>
 * <ol>
 *   <li><b>作用域只来自认证主体</b>：接口不接收租户参数，每次读写都用主体的租户进入作用域，
 *       并保留显式 {@code tenant_id} 谓词（与租户拦截器取同一个值，两条防线不互相掩盖）。</li>
 *   <li><b>角色只能从本租户已有角色里选</b>：越界的角色 id 直接拒绝，不静默忽略 ——
 *       静默忽略会让调用方以为授权成功了。</li>
 *   <li><b>不允许自锁</b>：不能停用自己，也不能改自己的角色。否则一次误操作就能让租户
 *       失去唯一的管理员，而恢复它需要平台侧介入。</li>
 * </ol>
 */
@Service
public class TenantMemberManageServiceImpl implements TenantMemberManageService {

    /** 登录名：字母数字与 {@code _ . -}，长度 3-64，与 {@code uk_user_tenant_username} 的列宽一致。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,64}$");

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final int ENABLED = 1;

    private static final int DISABLED = 0;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final int MAX_PAGE_SIZE = 100;

    private final AuthUserAccountMapper userAccountMapper;

    private final AuthUserRoleMapper userRoleMapper;

    private final AuthRoleMapper roleMapper;

    private final AuthRolePermissionMapper rolePermissionMapper;

    private final AuthPermissionMapper permissionMapper;

    private final PasswordVerifier passwordVerifier;

    private final UidGenerator uidGenerator;

    private final AuthAccountStore authAccountStore;

    public TenantMemberManageServiceImpl(AuthUserAccountMapper userAccountMapper,
                                        AuthUserRoleMapper userRoleMapper,
                                        AuthRoleMapper roleMapper,
                                        AuthRolePermissionMapper rolePermissionMapper,
                                        AuthPermissionMapper permissionMapper,
                                        PasswordVerifier passwordVerifier,
                                        UidGenerator uidGenerator,
                                        AuthAccountStore authAccountStore) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.passwordVerifier = passwordVerifier;
        this.uidGenerator = uidGenerator;
        this.authAccountStore = authAccountStore;
    }

    @Override
    public TenantMemberPageQueryVo queryPage(TenantMemberPageQueryDto dto) {
        RequestIdentity identity = requireIdentity();
        int pageNo = positiveOrDefault(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = Math.min(positiveOrDefault(dto == null ? null : dto.getPageSize(), DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);
        String keyword = StrUtil.trimToNull(dto == null ? null : dto.getKeyword());

        return TenantContext.callWith(identity.tenantId(), () -> {
            LambdaQueryWrapper<AuthUserAccount> wrapper = new LambdaQueryWrapper<AuthUserAccount>()
                .eq(AuthUserAccount::getTenantId, identity.tenantId())
                .orderByAsc(AuthUserAccount::getId);
            if (keyword != null) {
                wrapper.and(query -> query
                    .like(AuthUserAccount::getUsername, keyword)
                    .or()
                    .like(AuthUserAccount::getDisplayName, keyword));
            }

            Page<AuthUserAccount> page = userAccountMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
            List<AuthUserAccount> records = page.getRecords();
            Map<Long, List<AuthRole>> rolesByUser = rolesByUser(identity.tenantId(), records);
            Date now = new Date();
            List<TenantMemberItemVo> items = records.stream()
                .map(account -> toItem(account, rolesByUser.getOrDefault(account.getId(), List.of()), now))
                .toList();
            return new TenantMemberPageQueryVo(pageNo, pageSize, page.getTotal(), items);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantMemberItemVo save(TenantMemberSaveDto dto) {
        RequestIdentity identity = requireIdentity();
        Long memberId = parseOptionalLong(dto == null ? null : dto.getId());
        return TenantContext.callWith(identity.tenantId(), () -> {
            if (memberId == null) {
                return createMember(identity, dto);
            }
            return updateMember(identity, memberId, dto);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantMemberItemVo updateStatus(TenantMemberStatusUpdateDto dto) {
        RequestIdentity identity = requireIdentity();
        Long memberId = parseRequiredLong(dto == null ? null : dto.getId(), "成员id");
        Integer status = parseStatus(dto == null ? null : dto.getStatus());
        if (Objects.equals(identity.userId(), memberId)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "不能修改自己的启用状态。");
        }
        return TenantContext.callWith(identity.tenantId(), () -> {
            AuthUserAccount account = requireMember(identity.tenantId(), memberId);
            if (status == DISABLED) {
                assertNotLastEnabledAdmin(identity.tenantId(), memberId, "停用");
            }
            userAccountMapper.update(null, new LambdaUpdateWrapper<AuthUserAccount>()
                .eq(AuthUserAccount::getTenantId, identity.tenantId())
                .eq(AuthUserAccount::getId, memberId)
                .set(AuthUserAccount::getStatus, status)
                // 停用时同时清掉锁定状态：一个被停用的账号再次启用时不应带着历史的锁定截止时间。
                .set(status == DISABLED, AuthUserAccount::getLockedUntil, null)
                .set(status == DISABLED, AuthUserAccount::getFailedAttempts, 0));
            account.setStatus(status);
            account.setLockedUntil(null);
            if (status == DISABLED) {
                authAccountStore.incrementTokenVersion(identity.tenantId(), memberId);
            }
            return toItem(account, enabledRolesOfUser(identity.tenantId(), memberId), new Date());
        });
    }

    @Override
    public List<TenantRoleItemVo> listAssignableRoles() {
        RequestIdentity identity = requireIdentity();
        return TenantContext.callWith(identity.tenantId(), () -> {
            List<AuthRole> roles = roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
                .eq(AuthRole::getTenantId, identity.tenantId())
                .eq(AuthRole::getStatus, ENABLED)
                .orderByAsc(AuthRole::getId));
            Map<Long, List<String>> permissionCodesByRole = permissionCodesByRole(identity.tenantId(),
                roles.stream().map(AuthRole::getId).toList());
            return roles.stream()
                .map(role -> new TenantRoleItemVo(
                    role.getId(),
                    role.getRoleCode(),
                    role.getRoleName(),
                    role.getDescription(),
                    permissionCodesByRole.getOrDefault(role.getId(), List.of())))
                .toList();
        });
    }

    private TenantMemberItemVo createMember(RequestIdentity identity, TenantMemberSaveDto dto) {
        String username = StrUtil.trimToEmpty(dto == null ? null : dto.getUsername());
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "登录名只能包含字母、数字、下划线、点与连字符，长度 3-64。");
        }
        String password = dto == null ? null : dto.getPassword();
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "初始口令至少 " + MIN_PASSWORD_LENGTH + " 位。");
        }
        String displayName = StrUtil.trimToEmpty(dto == null ? null : dto.getDisplayName());
        if (displayName.isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "显示名不能为空。");
        }
        List<Long> roleIds = resolveAssignableRoleIds(identity.tenantId(), dto == null ? null : dto.getRoleIds());
        if (roleIds.isEmpty()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请至少为该成员分配一个角色。");
        }
        assertAssignableRoles(identity, roleIds);
        if (findByUsername(identity.tenantId(), username) != null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "该登录名已被使用。");
        }

        AuthUserAccount account = new AuthUserAccount();
        account.setId(uidGenerator.getUid());
        account.setTenantId(identity.tenantId());
        account.setUsername(username);
        // 口令只以 BCrypt 哈希落库；明文不进日志、不进返回值。
        account.setPasswordHash(passwordVerifier.encode(password));
        account.setDisplayName(displayName);
        account.setFailedAttempts(0);
        account.setStatus(ENABLED);
        userAccountMapper.insert(account);

        syncRoles(identity.tenantId(), account.getId(), roleIds);
        return toItem(account, enabledRolesOfUser(identity.tenantId(), account.getId()), new Date());
    }

    private TenantMemberItemVo updateMember(RequestIdentity identity, Long memberId, TenantMemberSaveDto dto) {
        AuthUserAccount account = requireMember(identity.tenantId(), memberId);
        String displayName = StrUtil.trimToEmpty(dto == null ? null : dto.getDisplayName());
        if (displayName.isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "显示名不能为空。");
        }
        List<Long> roleIds = resolveAssignableRoleIds(identity.tenantId(), dto == null ? null : dto.getRoleIds());
        if (roleIds.isEmpty()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请至少为该成员保留一个角色。");
        }
        boolean editingSelf = Objects.equals(identity.userId(), memberId);
        List<Long> currentRoleIds = enabledRoleIdsOfUser(identity.tenantId(), memberId);
        boolean rolesChanged = !new LinkedHashSet<>(currentRoleIds).equals(new LinkedHashSet<>(roleIds));
        if (editingSelf) {
            // 自锁防护：改自己的角色可能把自己降级到再也进不来管理端。
            if (rolesChanged) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "不能修改自己的角色。");
            }
        }
        assertAssignableRoles(identity, roleIds);
        if (rolesChanged && removesLastEnabledAdmin(identity.tenantId(), memberId, roleIds)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "不能降级租户内最后一名启用管理员。");
        }
        userAccountMapper.update(null, new LambdaUpdateWrapper<AuthUserAccount>()
            .eq(AuthUserAccount::getTenantId, identity.tenantId())
            .eq(AuthUserAccount::getId, memberId)
            .set(AuthUserAccount::getDisplayName, displayName));
        account.setDisplayName(displayName);
        syncRoles(identity.tenantId(), memberId, roleIds);
        if (rolesChanged) {
            authAccountStore.incrementTokenVersion(identity.tenantId(), memberId);
        }
        return toItem(account, rolesByIds(identity.tenantId(), roleIds), new Date());
    }

    /**
     * 把成员的角色集合同步成给定集合。
     *
     * <p>{@code uk_user_role(user_id, role_id)} 让"撤销后再授予"不能靠一次 insert 完成，
     * 因此这里按行存在与否决定 insert 还是复活（status=1），不删除历史行。</p>
     */
    private void syncRoles(Long tenantId, Long userId, List<Long> desiredRoleIds) {
        Set<Long> desired = new LinkedHashSet<>(desiredRoleIds);
        Map<Long, AuthUserRole> existing = new LinkedHashMap<>();
        for (AuthUserRole relation : userRoleMapper.selectList(new LambdaQueryWrapper<AuthUserRole>()
            .eq(AuthUserRole::getTenantId, tenantId)
            .eq(AuthUserRole::getUserId, userId))) {
            if (relation.getRoleId() != null) {
                existing.put(relation.getRoleId(), relation);
            }
        }
        for (Long roleId : desired) {
            AuthUserRole relation = existing.get(roleId);
            if (relation == null) {
                AuthUserRole created = new AuthUserRole();
                created.setId(uidGenerator.getUid());
                created.setTenantId(tenantId);
                created.setUserId(userId);
                created.setRoleId(roleId);
                created.setStatus(ENABLED);
                userRoleMapper.insert(created);
            }
            else if (!Objects.equals(relation.getStatus(), ENABLED)) {
                userRoleMapper.update(null, new LambdaUpdateWrapper<AuthUserRole>()
                    .eq(AuthUserRole::getTenantId, tenantId)
                    .eq(AuthUserRole::getId, relation.getId())
                    .set(AuthUserRole::getStatus, ENABLED));
            }
        }
        for (Map.Entry<Long, AuthUserRole> entry : existing.entrySet()) {
            if (!desired.contains(entry.getKey()) && Objects.equals(entry.getValue().getStatus(), ENABLED)) {
                userRoleMapper.update(null, new LambdaUpdateWrapper<AuthUserRole>()
                    .eq(AuthUserRole::getTenantId, tenantId)
                    .eq(AuthUserRole::getId, entry.getValue().getId())
                    .set(AuthUserRole::getStatus, DISABLED));
            }
        }
    }

    /** 角色 id 必须都是本租户的启用角色；有任何一个越界就整体拒绝。 */
    private List<Long> resolveAssignableRoleIds(Long tenantId, Collection<String> rawRoleIds) {
        if (rawRoleIds == null) {
            return List.of();
        }
        List<Long> requested = new ArrayList<>();
        for (String raw : rawRoleIds) {
            Long roleId = parseOptionalLong(raw);
            if (roleId == null) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "角色 id 格式非法。");
            }
            requested.add(roleId);
        }
        List<Long> distinct = requested.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        Set<Long> existing = roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
                .eq(AuthRole::getTenantId, tenantId)
                .eq(AuthRole::getStatus, ENABLED)
                .in(AuthRole::getId, distinct))
            .stream()
            .map(AuthRole::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Long roleId : distinct) {
            if (!existing.contains(roleId)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "存在不属于当前租户的角色。");
            }
        }
        return distinct;
    }

    private void assertAssignableRoles(RequestIdentity actor, List<Long> roleIds) {
        int actorRank = actorMaxRank(actor);
        Set<Long> assignedIds = new LinkedHashSet<>(roleIds);
        for (AuthRole role : rolesByIds(actor.tenantId(), roleIds)) {
            if (!assignedIds.contains(role.getId())) {
                continue;
            }
            if (roleRank(role.getRoleCode()) > actorRank) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "不能把成员提升到高于自己的角色。");
            }
        }
    }

    private int actorMaxRank(RequestIdentity actor) {
        Set<Long> actorRoleIds = actor.roleIds();
        return rolesByIds(actor.tenantId(), new ArrayList<>(actorRoleIds)).stream()
            .filter(role -> actorRoleIds.contains(role.getId()))
            .mapToInt(role -> roleRank(role.getRoleCode()))
            .max()
            .orElse(0);
    }

    private int roleRank(String roleCode) {
        if ("ADMIN".equalsIgnoreCase(roleCode)) {
            return 3;
        }
        if ("CURATOR".equalsIgnoreCase(roleCode)) {
            return 2;
        }
        if ("USER".equalsIgnoreCase(roleCode)) {
            return 1;
        }
        return 0;
    }

    private void assertNotLastEnabledAdmin(Long tenantId, Long userId, String action) {
        if (hasEnabledAdminRole(tenantId, userId) && countEnabledAdmins(tenantId) <= 1) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "不能" + action + "租户内最后一名启用管理员。");
        }
    }

    private boolean removesLastEnabledAdmin(Long tenantId, Long userId, List<Long> remainingRoleIds) {
        if (!hasEnabledAdminRole(tenantId, userId)) {
            return false;
        }
        Set<Long> remaining = new LinkedHashSet<>(remainingRoleIds);
        boolean stillAdmin = rolesByIds(tenantId, remainingRoleIds).stream()
            .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getRoleCode()) && remaining.contains(role.getId()));
        return !stillAdmin && countEnabledAdmins(tenantId) <= 1;
    }

    private boolean hasEnabledAdminRole(Long tenantId, Long userId) {
        return enabledRolesOfUser(tenantId, userId).stream()
            .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getRoleCode()));
    }

    private long countEnabledAdmins(Long tenantId) {
        List<AuthRole> adminRoles = roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
            .eq(AuthRole::getTenantId, tenantId)
            .eq(AuthRole::getRoleCode, "ADMIN")
            .eq(AuthRole::getStatus, ENABLED));
        List<Long> adminRoleIds = adminRoles.stream().map(AuthRole::getId).filter(Objects::nonNull).toList();
        if (adminRoleIds.isEmpty()) {
            return 0;
        }
        List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<AuthUserRole>()
                .eq(AuthUserRole::getTenantId, tenantId)
                .in(AuthUserRole::getRoleId, adminRoleIds)
                .eq(AuthUserRole::getStatus, ENABLED))
            .stream()
            .map(AuthUserRole::getUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (userIds.isEmpty()) {
            return 0;
        }
        Long count = userAccountMapper.selectCount(new LambdaQueryWrapper<AuthUserAccount>()
            .eq(AuthUserAccount::getTenantId, tenantId)
            .in(AuthUserAccount::getId, userIds)
            .eq(AuthUserAccount::getStatus, ENABLED));
        return count == null ? 0 : count;
    }

    private AuthUserAccount requireMember(Long tenantId, Long memberId) {
        AuthUserAccount account = userAccountMapper.selectOne(new LambdaQueryWrapper<AuthUserAccount>()
            .eq(AuthUserAccount::getTenantId, tenantId)
            .eq(AuthUserAccount::getId, memberId)
            .last("LIMIT 1"));
        if (account == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "成员不存在。");
        }
        return account;
    }

    private AuthUserAccount findByUsername(Long tenantId, String username) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<AuthUserAccount>()
            .eq(AuthUserAccount::getTenantId, tenantId)
            .eq(AuthUserAccount::getUsername, username)
            .last("LIMIT 1"));
    }

    private Map<Long, List<AuthRole>> rolesByUser(Long tenantId, List<AuthUserAccount> accounts) {
        List<Long> userIds = accounts.stream().map(AuthUserAccount::getId).filter(Objects::nonNull).toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<AuthUserRole> relations = userRoleMapper.selectList(new LambdaQueryWrapper<AuthUserRole>()
            .eq(AuthUserRole::getTenantId, tenantId)
            .in(AuthUserRole::getUserId, userIds)
            .eq(AuthUserRole::getStatus, ENABLED));
        Map<Long, AuthRole> roleById = rolesByIds(tenantId,
            relations.stream().map(AuthUserRole::getRoleId).filter(Objects::nonNull).distinct().toList()).stream()
            .collect(java.util.stream.Collectors.toMap(AuthRole::getId, role -> role, (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<AuthRole>> result = new LinkedHashMap<>();
        for (AuthUserRole relation : relations) {
            AuthRole role = relation.getRoleId() == null ? null : roleById.get(relation.getRoleId());
            if (role != null) {
                result.computeIfAbsent(relation.getUserId(), key -> new ArrayList<>()).add(role);
            }
        }
        return result;
    }

    private List<Long> enabledRoleIdsOfUser(Long tenantId, Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<AuthUserRole>()
                .eq(AuthUserRole::getTenantId, tenantId)
                .eq(AuthUserRole::getUserId, userId)
                .eq(AuthUserRole::getStatus, ENABLED))
            .stream()
            .map(AuthUserRole::getRoleId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<AuthRole> enabledRolesOfUser(Long tenantId, Long userId) {
        return rolesByIds(tenantId, enabledRoleIdsOfUser(tenantId, userId));
    }

    private List<AuthRole> rolesByIds(Long tenantId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
            .eq(AuthRole::getTenantId, tenantId)
            .in(AuthRole::getId, roleIds)
            .orderByAsc(AuthRole::getId));
    }

    private Map<Long, List<String>> permissionCodesByRole(Long tenantId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        List<AuthRolePermission> relations = rolePermissionMapper.selectList(new LambdaQueryWrapper<AuthRolePermission>()
            .eq(AuthRolePermission::getTenantId, tenantId)
            .in(AuthRolePermission::getRoleId, roleIds)
            .eq(AuthRolePermission::getStatus, ENABLED));
        Map<Long, String> codeById = new LinkedHashMap<>();
        List<Long> permissionIds = relations.stream()
            .map(AuthRolePermission::getPermissionId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (!permissionIds.isEmpty()) {
            // 权限字典是全局表（无 tenant_id），因此这次读取在系统作用域内执行。
            for (AuthPermission permission : TenantContext.callAsSystem(() -> permissionMapper.selectList(
                new LambdaQueryWrapper<AuthPermission>()
                    .in(AuthPermission::getId, permissionIds)
                    .eq(AuthPermission::getStatus, ENABLED)))) {
                codeById.put(permission.getId(), permission.getPermissionCode());
            }
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (AuthRolePermission relation : relations) {
            String code = relation.getPermissionId() == null ? null : codeById.get(relation.getPermissionId());
            if (code != null && !code.isBlank()) {
                result.computeIfAbsent(relation.getRoleId(), key -> new ArrayList<>()).add(code);
            }
        }
        return result;
    }

    private TenantMemberItemVo toItem(AuthUserAccount account, List<AuthRole> roles, Date now) {
        List<Long> roleIds = roles.stream().map(AuthRole::getId).toList();
        List<String> roleCodes = roles.stream().map(AuthRole::getRoleCode).toList();
        List<String> roleNames = roles.stream().map(AuthRole::getRoleName).toList();
        boolean locked = account.getLockedUntil() != null && account.getLockedUntil().after(now);
        return new TenantMemberItemVo(
            account.getId(),
            account.getUsername(),
            account.getDisplayName(),
            account.getStatus(),
            roleIds,
            roleCodes,
            roleNames,
            account.getLastLoginAt() == null ? null : account.getLastLoginAt().getTime(),
            locked);
    }

    private RequestIdentity requireIdentity() {
        RequestIdentity identity = TenantContext.getIdentity();
        if (identity == null) {
            throw new AuthFailureException(401, "请先登录");
        }
        return identity;
    }

    private Integer parseStatus(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "状态不能为空。");
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value != ENABLED && value != DISABLED) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "状态只能是 1（启用）或 0（停用）。");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "状态只能是 1（启用）或 0（停用）。");
        }
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        Long value = parseOptionalLong(rawValue);
        if (value == null || value <= 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        return value;
    }

    private Long parseOptionalLong(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        try {
            return Long.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 格式非法。");
        }
    }

    private int positiveOrDefault(String rawValue, int fallback) {
        if (StrUtil.isBlank(rawValue)) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            return value <= 0 ? fallback : value;
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
