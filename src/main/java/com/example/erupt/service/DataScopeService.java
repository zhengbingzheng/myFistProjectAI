package com.example.erupt.service;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.RequestScopeCache;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.ResultCode;
import com.example.erupt.repository.entity.Role;
import com.example.erupt.repository.mapper.MenuMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据可见范围（行级权限）判定。
 *
 * <p>规则只有一条但贯穿所有业务模块，所以单独抽出来而不是散在各 Service 里：
 * <b>超级管理员看全部，其余人只看自己创建的</b>。</p>
 *
 * <p><b>为什么不在 SQL 里写「is_admin」子查询？</b>
 * 那样每条 SQL 都要 JOIN 一次 t_user_role / t_role，索引再好也是几万行的连接；
 * 而角色在一次请求内不会变，查一次、把结果当参数传下去是最省的做法。
 * 代价是调用方必须先拿到 {@link #dataScopeOwnerId()} 再进查询，不能漏传——
 * 漏传的后果是「看到别人的数据」，所以每个 Service 方法入口都显式取一次。</p>
 *
 * <p><b>为什么用 role_code 而不是 role_level == 0？</b>
 * 等级是「数字越小越高」的相对值，将来若新增 level 为 1 的二级管理员，
 * 用等级判断会把它一并当超管；role_code 是稳定的程序标识，改等级不影响判定。</p>
 */
@Service
public class DataScopeService {

    /** 超级管理员的角色编码，与 db/schema.sql 中的初始角色一致 */
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

    private final MenuMapper menuMapper;

    public DataScopeService(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /** 当前登录用户是否超级管理员；未登录时返回 false（宁可少给权限） */
    public boolean isSuperAdmin() {
        String userId = AuthContext.currentUserId();
        if (userId == null) {
            return false;
        }
        Boolean cached = RequestScopeCache.superAdmin();
        if (cached != null) {
            return cached;
        }
        List<Role> roles = menuMapper.selectRolesByUserId(userId);
        for (Role role : roles) {
            if (SUPER_ADMIN_ROLE.equals(role.getRoleCode())) {
                RequestScopeCache.rememberSuperAdmin(true);
                return true;
            }
        }
        RequestScopeCache.rememberSuperAdmin(false);
        return false;
    }

    /**
     * 当前用户的数据范围。
     *
     * @return {@code null} 表示不限制（超级管理员）；否则为该用户 id，
     *         查询时必须用 {@code create_by = 该值} 过滤
     * @throws BusinessException 未登录
     */
    public String dataScopeOwnerId() {
        String userId = AuthContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return isSuperAdmin() ? null : userId;
    }

    /**
     * 校验某条数据是否在当前用户的可见范围内。
     *
     * <p>用 403 而不是 404：这个系统里「计划」不是敏感资源，
     * 明确告诉用户「这不是你的」，比伪造成「不存在」更省沟通成本
     * （否则用户会反复刷新、以为是数据丢了）。</p>
     *
     * @param scopeOwnerId 范围值，{@code null} 表示超管，直接放行
     * @param dataOwnerId  数据行的 create_by
     */
    public void requireVisible(String scopeOwnerId, String dataOwnerId, String resourceName) {
        if (scopeOwnerId == null) {
            return;
        }
        if (!scopeOwnerId.equals(dataOwnerId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "无权访问该" + resourceName + "：只能操作自己创建的数据");
        }
    }
}
