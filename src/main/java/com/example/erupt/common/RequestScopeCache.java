package com.example.erupt.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 单次请求内的鉴权结果缓存。
 *
 * <p>菜单权限和是否超管在一次请求里不会变，但甘特、搜索会反复问。
 * 每次都查库只是在重复同一条主键查询。缓存放在 ThreadLocal，
 * 必须由 {@code AuthInterceptor} 在请求进出时清掉，否则线程池会把上一个用户的结论带给下一个人。</p>
 */
public final class RequestScopeCache {

    private static final ThreadLocal<Map<String, Boolean>> PERMISSIONS =
            ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Boolean> SUPER_ADMIN = new ThreadLocal<>();

    private RequestScopeCache() {
    }

    public static void clear() {
        PERMISSIONS.remove();
        SUPER_ADMIN.remove();
    }

    public static Boolean permission(String userId, String permission) {
        return PERMISSIONS.get().get(key(userId, permission));
    }

    public static void rememberPermission(String userId, String permission, boolean allowed) {
        PERMISSIONS.get().put(key(userId, permission), allowed);
    }

    public static Boolean superAdmin() {
        return SUPER_ADMIN.get();
    }

    public static void rememberSuperAdmin(boolean value) {
        SUPER_ADMIN.set(value);
    }

    private static String key(String userId, String permission) {
        return userId + "\0" + permission;
    }
}
