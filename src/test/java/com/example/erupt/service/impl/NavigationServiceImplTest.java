package com.example.erupt.service.impl;

import com.example.erupt.cache.NavigationCache;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.repository.entity.Navigation;
import com.example.erupt.repository.mapper.NavigationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NavigationServiceImplTest {

    @Mock
    private NavigationMapper navigationMapper;

    @Mock
    private NavigationCache navigationCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private NavigationServiceImpl navigationService;

    /**
     * 让缓存层默认"透传"：直接执行业务传入的回源逻辑。
     *
     * <p>Mockito 对未 stub 的方法只会返回 null，<strong>不会</strong>执行入参里的 Supplier，
     * 那样 page / listCats 等方法会返回 null 而不是真实数据。这里统一 stub 成调用 loader，
     * 等价于运行在"缓存永远未命中"的环境里，因此既有 25 个用例的断言语义保持不变。</p>
     *
     * <p>用 {@code lenient()} 是因为并非每个用例都会走到全部四个方法，
     * Mockito 严格模式下未被使用的 stub 会抛 {@code UnnecessaryStubbingException}。</p>
     */
    @BeforeEach
    void makeCachePassThrough() {
        lenient().when(navigationCache.detail(any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        lenient().when(navigationCache.page(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(5).get());
        lenient().when(navigationCache.cats(any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        lenient().when(navigationCache.allCats(any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
    }

    @Test
    @DisplayName("create_validRequest_insertsAndReturnsSuccess")
    void create_validRequest_insertsAndReturnsSuccess() {
        NavigationCreateRequest request = new NavigationCreateRequest("Nginx UI", "https://nginxui.com/",
                "/img/a.jpg", "运维", "nginx ui", "nginx ui");
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(1);

        Result<Void> result = navigationService.create(request);

        ArgumentCaptor<Navigation> captor = ArgumentCaptor.forClass(Navigation.class);
        verify(navigationMapper).insert(captor.capture());
        assertThat(captor.getValue().getNavigationName()).isEqualTo("Nginx UI");
        assertThat(captor.getValue().getNavigationUrl()).isEqualTo("https://nginxui.com/");
        assertThat(captor.getValue().getCat()).isEqualTo("运维");
        assertThat(captor.getValue().getDescription()).isEqualTo("nginx ui");
        assertThat(captor.getValue().getCreateTime()).isNotNull();
        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("新增成功");
    }

    @Test
    @DisplayName("create_affectedRowsZero_returnsFailure")
    void create_affectedRowsZero_returnsFailure() {
        NavigationCreateRequest request = new NavigationCreateRequest("Nginx UI", "https://nginxui.com/",
                "/img/a.jpg", "运维", "nginx ui", "nginx ui");
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(0);

        Result<Void> result = navigationService.create(request);

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("新增失败");
        verify(navigationMapper).insert(any(Navigation.class));
    }

    @Test
    @DisplayName("update_notFound_throwsNotFoundException")
    void update_notFound_throwsNotFoundException() {
        when(navigationMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> navigationService.update(99L, new NavigationUpdateRequest("新名称", null, null, null,
                null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(navigationMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("update_partialFields_keepsUntouchedValues")
    void update_partialFields_keepsUntouchedValues() {
        when(navigationMapper.selectById(1L)).thenReturn(mockNavigation(1L, "原名", "http://old", "运维", "原描述"));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(1);

        Result<Void> result = navigationService.update(1L,
                new NavigationUpdateRequest("新名", null, null, null, null, null));
        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("更新成功");

        ArgumentCaptor<Navigation> captor = ArgumentCaptor.forClass(Navigation.class);
        verify(navigationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNavigationName()).isEqualTo("新名");
        assertThat(captor.getValue().getNavigationUrl()).isEqualTo("http://old");
        assertThat(captor.getValue().getCat()).isEqualTo("运维");
        assertThat(captor.getValue().getDescription()).isEqualTo("原描述");
    }

    @Test
    @DisplayName("update_affectedRowsZero_returnsFalse")
    void update_affectedRowsZero_returnsFalse() {
        when(navigationMapper.selectById(1L)).thenReturn(mockNavigation(1L, "原名", "http://old", "运维", "原描述"));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(0);

        Result<Void> result = navigationService.update(1L,
                new NavigationUpdateRequest("新名", null, null, null, null, null));
        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("更新失败");
        verify(navigationMapper).updateById(any(Navigation.class));
    }

    @Test
    @DisplayName("getById_notFound_throwsNotFoundException")
    void getById_notFound_throwsNotFoundException() {
        when(navigationMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> navigationService.getById(404L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getById_existing_returnsVO")
    void getById_existing_returnsVO() {
        when(navigationMapper.selectById(1L)).thenReturn(mockNavigation(1L, "Nginx UI", "https://nginxui.com/",
                "运维", "nginx ui"));

        NavigationVO vo = navigationService.getById(1L);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.name()).isEqualTo("Nginx UI");
        assertThat(vo.cat()).isEqualTo("运维");
        assertThat(vo.description()).isEqualTo("nginx ui");
    }

    @Test
    @DisplayName("page_noMatch_skipsListQuery")
    void page_noMatch_skipsListQuery() {
        when(navigationMapper.countByCondition(null, null, null)).thenReturn(0L);

        PageResult<NavigationVO> result = navigationService.page(new NavigationQueryRequest(null, null, null, 1, 10));

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        verify(navigationMapper, never()).selectByCondition(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("page_withData_returnsPageResult")
    void page_withData_returnsPageResult() {
        when(navigationMapper.countByCondition(null, "运维", null)).thenReturn(2L);
        when(navigationMapper.selectByCondition(null, "运维", null, 0L, 10))
                .thenReturn(List.of(mockNavigation(2L, "A", "http://a", "运维", "da"),
                        mockNavigation(1L, "B", "http://b", "运维", "db")));

        PageResult<NavigationVO> result = navigationService.page(new NavigationQueryRequest(null, "运维", null, 1, 10));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.items()).extracting(NavigationVO::name).containsExactly("A", "B");
    }

    @Test
    @DisplayName("delete_notFound_throwsNotFoundException")
    void delete_notFound_throwsNotFoundException() {
        when(navigationMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> navigationService.delete(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(navigationMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete_existing_deletesRow")
    void delete_existing_deletesRow() {
        when(navigationMapper.selectById(7L)).thenReturn(mockNavigation(7L, "A", "http://a", "运维", "da"));
        when(navigationMapper.deleteById(7L)).thenReturn(1);

        Result<Void> result = navigationService.delete(7L);
        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("删除成功");
        verify(navigationMapper).deleteById(7L);
    }

    @Test
    @DisplayName("delete_affectedRowsZero_returnsFalse")
    void delete_affectedRowsZero_returnsFalse() {
        when(navigationMapper.selectById(7L)).thenReturn(mockNavigation(7L, "A", "http://a", "运维", "da"));
        when(navigationMapper.deleteById(7L)).thenReturn(0);

        Result<Void> result = navigationService.delete(7L);
        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("删除失败");
        verify(navigationMapper).deleteById(7L);
    }

    @Test
    @DisplayName("listCats_returnsDistinctCats_trimAndDropBlank")
    void listCats_returnsDistinctCats_trimAndDropBlank() {
        when(navigationMapper.selectDistinctCats())
                .thenReturn(Arrays.asList("办公AI", " 办公AI ", "大厂AI", " ", null, "写作AI", "写作AI"));

        List<String> cats = navigationService.listCats();

        assertThat(cats).containsExactly("办公AI", "大厂AI", "写作AI");
        verify(navigationMapper).selectDistinctCats();
    }

    @Test
    @DisplayName("listCats_emptyResult_returnsEmptyList")
    void listCats_emptyResult_returnsEmptyList() {
        when(navigationMapper.selectDistinctCats()).thenReturn(Collections.emptyList());

        assertThat(navigationService.listCats()).isEmpty();
    }

    @Test
    @DisplayName("listCats_nullResult_returnsEmptyList")
    void listCats_nullResult_returnsEmptyList() {
        when(navigationMapper.selectDistinctCats()).thenReturn(null);

        assertThat(navigationService.listCats()).isEmpty();
    }

    @Test
    @DisplayName("listCats_allBlank_returnsEmptyList")
    void listCats_allBlank_returnsEmptyList() {
        when(navigationMapper.selectDistinctCats()).thenReturn(Arrays.asList("", "   ", null));

        assertThat(navigationService.listCats()).isEmpty();
    }

    @Test
    @DisplayName("listAllCats_keepsDuplicatesInOrder")
    void listAllCats_keepsDuplicatesInOrder() {
        when(navigationMapper.selectAllCats())
                .thenReturn(Arrays.asList("办公AI", "办公AI", "大厂AI", " 运维 ", null, "", "大厂AI"));

        List<String> cats = navigationService.listAllCats();

        assertThat(cats).containsExactly("办公AI", "办公AI", "大厂AI", "运维", "大厂AI");
        verify(navigationMapper).selectAllCats();
    }

    @Test
    @DisplayName("listAllCats_nullResult_returnsEmptyList")
    void listAllCats_nullResult_returnsEmptyList() {
        when(navigationMapper.selectAllCats()).thenReturn(null);

        assertThat(navigationService.listAllCats()).isEmpty();
    }

    @Test
    @DisplayName("deleteBatch_allIdsExist_returnsSuccess")
    void deleteBatch_allIdsExist_returnsSuccess() {
        when(navigationMapper.countByIds(List.of(1L, 2L))).thenReturn(2L);
        when(navigationMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        Result<Void> result = navigationService.deleteBatch(List.of(1L, 2L));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("批量删除成功，共删除 2 条");
        verify(navigationMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete")
    void deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete() {
        when(navigationMapper.countByIds(List.of(1L, 999L))).thenReturn(1L);

        assertThatThrownBy(() -> navigationService.deleteBatch(List.of(1L, 999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(navigationMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteBatch_emptyIds_throwsBadRequest")
    void deleteBatch_emptyIds_throwsBadRequest() {
        assertThatThrownBy(() -> navigationService.deleteBatch(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(navigationMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteBatch_duplicateIds_deduplicatesBeforeDelete")
    void deleteBatch_duplicateIds_deduplicatesBeforeDelete() {
        when(navigationMapper.countByIds(List.of(1L, 2L))).thenReturn(2L);
        when(navigationMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        navigationService.deleteBatch(List.of(1L, 2L, 1L, 2L));

        verify(navigationMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("deleteBatch_affectedRowsZero_returnsFailure")
    void deleteBatch_affectedRowsZero_returnsFailure() {
        when(navigationMapper.countByIds(List.of(1L))).thenReturn(1L);
        when(navigationMapper.deleteByIds(List.of(1L))).thenReturn(0);

        Result<Void> result = navigationService.deleteBatch(List.of(1L));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("批量删除失败");
    }

    private Navigation mockNavigation(Long id, String name, String url, String cat, String description) {
        Navigation navigation = new Navigation();
        navigation.setId(id);
        navigation.setNavigationName(name);
        navigation.setNavigationUrl(url);
        navigation.setNavigationImg("/img/" + id + ".png");
        navigation.setCat(cat);
        navigation.setDescription(description);
        navigation.setTag("tag-" + id);
        navigation.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        navigation.setUpdateTime(LocalDateTime.of(2026, 1, 2, 0, 0));
        return navigation;
    }

}
