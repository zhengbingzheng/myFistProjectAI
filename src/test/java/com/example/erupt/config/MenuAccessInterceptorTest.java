package com.example.erupt.config;

import com.example.erupt.common.AuthContext;
import com.example.erupt.service.MenuService;
import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MenuAccessInterceptor} 的放行与拦截规则测试。
 *
 * <p>重点验证数据字典的三个读接口对所有登录用户开放（不再要求 dict 菜单授权），
 * 而写操作与其它模块仍然按菜单授权拦截。</p>
 */
@ExtendWith(MockitoExtension.class)
class MenuAccessInterceptorTest {

    @Mock
    private MenuService menuService;

    private MenuAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new MenuAccessInterceptor(menuService);
        // 模拟一个已登录用户：拦截器靠 AuthContext 拿身份，不设会走"未登录放行"分支
        AuthContext.set(new TokenService.TokenIdentity("u-1", "someone", Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        // 测试线程会被复用，必须清理 ThreadLocal，否则身份会串到下一个用例
        AuthContext.clear();
    }

    @Test
    @DisplayName("字典类型分页 GET：未授权 dict 的登录用户也放行")
    void dictTypesPage_sharedReadOnly() {
        MockHttpServletRequest request = request("GET", "/api/dict/types");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        // 共享只读路径应当直接放行，连 hasPermission 都不该查
        verify(menuService, never()).hasPermission(eq("u-1"), eq("dict"));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("字典项分页 GET：未授权 dict 的登录用户也放行")
    void dictItemsPage_sharedReadOnly() {
        MockHttpServletRequest request = request("GET", "/api/dict/items");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        verify(menuService, never()).hasPermission(eq("u-1"), eq("dict"));
    }

    @Test
    @DisplayName("字典下拉 GET：对所有登录用户开放（既有行为）")
    void dictEnabledItems_sharedReadOnly() {
        MockHttpServletRequest request = request("GET", "/api/dict/sys_user_status/items");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        verify(menuService, never()).hasPermission(eq("u-1"), eq("dict"));
    }

    @Test
    @DisplayName("字典写操作 POST：未授权 dict 的登录用户被 403 拦截")
    void dictTypeCreate_forbiddenWithoutPermission() {
        MockHttpServletRequest request = request("POST", "/api/dict/types");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(menuService.hasPermission("u-1", "dict")).thenReturn(false);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("字典写操作 DELETE：未授权 dict 的登录用户被 403 拦截")
    void dictItemDelete_forbiddenWithoutPermission() {
        MockHttpServletRequest request = request("DELETE", "/api/dict/items/item-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(menuService.hasPermission("u-1", "dict")).thenReturn(false);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("字典写操作：有 dict 菜单授权时放行")
    void dictWrite_allowedWithPermission() {
        MockHttpServletRequest request = request("POST", "/api/dict/types");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(menuService.hasPermission("u-1", "dict")).thenReturn(true);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("其它模块仍按菜单授权：未授权拦截、已授权放行")
    void otherModule_permissionStillRequired() {
        MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
        when(menuService.hasPermission("u-1", "knowledge")).thenReturn(false);
        assertThat(interceptor.preHandle(request("GET", "/api/knowledge"), forbiddenResponse, new Object()))
                .isFalse();
        assertThat(forbiddenResponse.getStatus()).isEqualTo(403);

        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        when(menuService.hasPermission("u-1", "knowledge")).thenReturn(true);
        assertThat(interceptor.preHandle(request("GET", "/api/knowledge"), allowedResponse, new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("全局搜索精确放行，同名前缀的其它路径仍要鉴权")
    void searchIsExactNotPrefix() {
        MockHttpServletResponse search = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/search"), search, new Object())).isTrue();
        verify(menuService, never()).hasPermission(eq("u-1"), eq("search"));

        when(menuService.hasPermission("u-1", "search-admin")).thenReturn(false);
        MockHttpServletResponse other = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/search-admin"), other, new Object())).isFalse();
        assertThat(other.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("未登录请求本拦截器不拦截，由 AuthInterceptor 负责 401")
    void notLoggedIn_passThrough() {
        AuthContext.clear();
        MockHttpServletRequest request = request("GET", "/api/knowledge");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
