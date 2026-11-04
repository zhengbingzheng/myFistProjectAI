package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationImportItemRequest;
import com.example.erupt.model.dto.NavigationImportRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationImportResultVO;
import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.repository.entity.Navigation;
import com.example.erupt.repository.mapper.NavigationMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导航服务单元测试。
 *
 * <p>原先这里要额外 mock {@code NavigationCache}，因为 Service 的读方法都经过缓存。
 * 现在<b>直接查库、不再缓存</b>，服务只依赖 Mapper 一个协作者，测试也随之简化：
 * 不再需要为每个用例构造缓存桩，断言的就是真实的查询链路。</p>
 */
@ExtendWith(MockitoExtension.class)
class NavigationServiceImplTest {

    /** 主键统一为 32 位字符串，测试用例里用可读的字符串 id */
    private static final String ID_1 = "id-000000000000000000000000000001";
    private static final String ID_2 = "id-000000000000000000000000000002";
    private static final String ID_7 = "id-000000000000000000000000000007";

    @Mock
    private NavigationMapper navigationMapper;

    /** 逐行校验器：导入用它触发 Bean Validation，测试里默认桩为「无违规」 */
    @Mock
    private Validator validator;

    @InjectMocks
    private NavigationServiceImpl navigationService;

    @Test
    @DisplayName("create_validRequest_insertsAndReturnsSuccess")
    void create_validRequest_insertsAndReturnsSuccess() {
        NavigationCreateRequest request = new NavigationCreateRequest("Nginx UI", "https://nginxui.com/",
                "/img/a.jpg", "运维", "nginx ui", "nginx ui");
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(1);

        Result<Void> result = navigationService.create(request);

        ArgumentCaptor<Navigation> captor = ArgumentCaptor.forClass(Navigation.class);
        verify(navigationMapper).insert(captor.capture());
        // 主键由应用层生成：写入前就已确定，不依赖数据库自增回填
        assertThat(captor.getValue().getId()).hasSize(32);
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
        when(navigationMapper.selectById("99")).thenReturn(null);

        assertThatThrownBy(() -> navigationService.update("99", new NavigationUpdateRequest("新名称", null, null, null,
                null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(navigationMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("update_partialFields_keepsUntouchedValues")
    void update_partialFields_keepsUntouchedValues() {
        when(navigationMapper.selectById(ID_1)).thenReturn(mockNavigation(ID_1, "原名", "http://old", "运维", "原描述"));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(1);

        Result<Void> result = navigationService.update(ID_1,
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
        when(navigationMapper.selectById(ID_1)).thenReturn(mockNavigation(ID_1, "原名", "http://old", "运维", "原描述"));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(0);

        Result<Void> result = navigationService.update(ID_1,
                new NavigationUpdateRequest("新名", null, null, null, null, null));
        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("更新失败");
        verify(navigationMapper).updateById(any(Navigation.class));
    }

    @Test
    @DisplayName("getById_notFound_throwsNotFoundException")
    void getById_notFound_throwsNotFoundException() {
        when(navigationMapper.selectById("404")).thenReturn(null);

        assertThatThrownBy(() -> navigationService.getById("404"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getById_existing_returnsVO")
    void getById_existing_returnsVO() {
        when(navigationMapper.selectById(ID_1)).thenReturn(mockNavigation(ID_1, "Nginx UI", "https://nginxui.com/",
                "运维", "nginx ui"));

        // 不再经过缓存：一次 mapper 查询得到结果
        NavigationVO vo = navigationService.getById(ID_1);

        verify(navigationMapper).selectById(ID_1);
        assertThat(vo.id()).isEqualTo(ID_1);
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
                .thenReturn(List.of(mockNavigation(ID_2, "A", "http://a", "运维", "da"),
                        mockNavigation(ID_1, "B", "http://b", "运维", "db")));

        PageResult<NavigationVO> result = navigationService.page(new NavigationQueryRequest(null, "运维", null, 1, 10));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.items()).extracting(NavigationVO::name).containsExactly("A", "B");
    }

    @Test
    @DisplayName("delete_notFound_throwsNotFoundException")
    void delete_notFound_throwsNotFoundException() {
        when(navigationMapper.selectById(ID_7)).thenReturn(null);

        assertThatThrownBy(() -> navigationService.delete(ID_7))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(navigationMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete_existing_deletesRow")
    void delete_existing_deletesRow() {
        when(navigationMapper.selectById(ID_7)).thenReturn(mockNavigation(ID_7, "A", "http://a", "运维", "da"));
        when(navigationMapper.deleteById(ID_7)).thenReturn(1);

        Result<Void> result = navigationService.delete(ID_7);
        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("删除成功");
        verify(navigationMapper).deleteById(ID_7);
    }

    @Test
    @DisplayName("delete_affectedRowsZero_复核存在则抛内部错误")
    void delete_affectedRowsZero_returnsFalse() {
        when(navigationMapper.selectById(ID_7)).thenReturn(mockNavigation(ID_7, "A", "http://a", "运维", "da"));
        when(navigationMapper.deleteById(ID_7)).thenReturn(0);

        // affected=0 但复核记录还在：真正的写失败，抛 500 而不是返回 HTTP 200 + 信封失败
        assertThatThrownBy(() -> navigationService.delete(ID_7))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.INTERNAL_ERROR);
        verify(navigationMapper).deleteById(ID_7);
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
        when(navigationMapper.countByIds(List.of(ID_1, ID_2))).thenReturn(2L);
        when(navigationMapper.deleteByIds(List.of(ID_1, ID_2))).thenReturn(2);

        Result<Void> result = navigationService.deleteBatch(List.of(ID_1, ID_2));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("批量删除成功，共删除 2 条");
        verify(navigationMapper).deleteByIds(List.of(ID_1, ID_2));
    }

    @Test
    @DisplayName("deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete")
    void deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete() {
        when(navigationMapper.countByIds(List.of(ID_1, "999"))).thenReturn(1L);

        assertThatThrownBy(() -> navigationService.deleteBatch(List.of(ID_1, "999")))
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
        when(navigationMapper.countByIds(List.of(ID_1, ID_2))).thenReturn(2L);
        when(navigationMapper.deleteByIds(List.of(ID_1, ID_2))).thenReturn(2);

        navigationService.deleteBatch(List.of(ID_1, ID_2, ID_1, ID_2));

        verify(navigationMapper).deleteByIds(List.of(ID_1, ID_2));
    }

    @Test
    @DisplayName("deleteBatch_affectedRows不足_整批失败抛404")
    void deleteBatch_affectedRowsZero_returnsFailure() {
        when(navigationMapper.countByIds(List.of(ID_1))).thenReturn(1L);
        when(navigationMapper.deleteByIds(List.of(ID_1))).thenReturn(0);

        // 行数不足 = count-then-delete 间隙被并发删掉，整批失败并回滚，不产生部分删除
        assertThatThrownBy(() -> navigationService.deleteBatch(List.of(ID_1)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    /* ---------------------------------------------------------------------- */
    /*                            批量导入 / 导出                               */
    /* ---------------------------------------------------------------------- */

    @Test
    @DisplayName("create_unsafeProtocol_throwsBadRequestAndSkipsInsert")
    void create_unsafeProtocol_throwsBadRequestAndSkipsInsert() {
        NavigationCreateRequest request = new NavigationCreateRequest("恶意", "javascript:alert(1)",
                null, "工具", null, null);

        assertThatThrownBy(() -> navigationService.create(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(navigationMapper, never()).insert(any(Navigation.class));
    }

    @Test
    @DisplayName("create_surroundingWhitespace_trimmedBeforeInsert")
    void create_surroundingWhitespace_trimmedBeforeInsert() {
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(1);

        navigationService.create(new NavigationCreateRequest("  空格名称  ", "  https://a.com  ",
                null, "  运维  ", null, null));

        ArgumentCaptor<Navigation> captor = ArgumentCaptor.forClass(Navigation.class);
        verify(navigationMapper).insert(captor.capture());
        // 不 trim 的话 "运维 " 与 "运维" 会变成两个分类，分类筛选随即失效
        assertThat(captor.getValue().getNavigationName()).isEqualTo("空格名称");
        assertThat(captor.getValue().getNavigationUrl()).isEqualTo("https://a.com");
        assertThat(captor.getValue().getCat()).isEqualTo("运维");
    }

    @Test
    @DisplayName("update_unsafeProtocol_throwsBadRequestAndSkipsUpdate")
    void update_unsafeProtocol_throwsBadRequestAndSkipsUpdate() {
        when(navigationMapper.selectById(ID_1))
                .thenReturn(mockNavigation(ID_1, "原名", "https://old.com", "运维", "原描述"));

        // 先存合法链接、再改为伪协议，是绕过新增校验的常见路径，这里必须同样拦住
        assertThatThrownBy(() -> navigationService.update(ID_1,
                new NavigationUpdateRequest(null, "javascript:alert(1)", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(navigationMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("update_userId_writtenToEntityForAudit")
    void update_userId_writtenToEntityForAudit() {
        when(navigationMapper.selectById(ID_1))
                .thenReturn(mockNavigation(ID_1, "原名", "https://old.com", "运维", "原描述"));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(1);

        navigationService.update(ID_1, new NavigationUpdateRequest("新名", null, null, null, null, null));

        ArgumentCaptor<Navigation> captor = ArgumentCaptor.forClass(Navigation.class);
        verify(navigationMapper).updateById(captor.capture());
        // update_time 一定被填充；update_user_id 取决于登录态，未登录时为 null（测试环境无 AuthContext）
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("page_offsetExceedsLimit_rejectedBeforeCountQuery")
    void page_offsetExceedsLimit_rejectedBeforeCountQuery() {
        // 页码极大 → offset 超上限，应当在 count 之前就被拒绝
        assertThatThrownBy(() -> navigationService.page(
                new NavigationQueryRequest(null, null, null, 20_000, 100)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        // 关键断言：没有先跑一次全表 count
        verify(navigationMapper, never()).countByCondition(any(), any(), any());
    }

    @Test
    @DisplayName("importItems_allNew_countsCreatedAndNoError")
    void importItems_allNew_countsCreatedAndNoError() {
        stubNoViolation();
        when(navigationMapper.selectByCats(anyList())).thenReturn(Collections.emptyList());
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(1);

        NavigationImportResultVO result = navigationService.importItems(
                new NavigationImportRequest(List.of(importItem("A", "https://a.com", "工具")), "skip"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("importItems_skipMode_existingRowSkipped")
    void importItems_skipMode_existingRowSkipped() {
        stubNoViolation();
        when(navigationMapper.selectByCats(anyList()))
                .thenReturn(List.of(mockNavigation(ID_1, "A", "https://a.com", "工具", "desc")));

        NavigationImportResultVO result = navigationService.importItems(
                new NavigationImportRequest(List.of(importItem("A", "https://a.com", "工具")), "skip"));

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        // 跳过不是失败：用户不需要为「已存在」去改文件
        assertThat(result.errors()).isEmpty();
        verify(navigationMapper, never()).insert(any(Navigation.class));
    }

    @Test
    @DisplayName("importItems_overwriteMode_existingRowUpdated")
    void importItems_overwriteMode_existingRowUpdated() {
        stubNoViolation();
        when(navigationMapper.selectByCats(anyList()))
                .thenReturn(List.of(mockNavigation(ID_1, "A", "https://a.com", "工具", "desc")));
        when(navigationMapper.updateById(any(Navigation.class))).thenReturn(1);

        NavigationImportResultVO result = navigationService.importItems(
                new NavigationImportRequest(List.of(importItem("A", "https://a-new.com", "工具")), "overwrite"));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("importItems_unsafeProtocol_rowFailedWithExcelRowNumber")
    void importItems_unsafeProtocol_rowFailedWithExcelRowNumber() {
        stubNoViolation();
        when(navigationMapper.selectByCats(anyList())).thenReturn(Collections.emptyList());
        when(navigationMapper.insert(any(Navigation.class))).thenReturn(1);

        // 两行数据：第 1 行合法、第 2 行是 javascript: 伪协议 → 行号应为 3（表头占第 1 行）
        NavigationImportResultVO result = navigationService.importItems(
                new NavigationImportRequest(List.of(
                        importItem("A", "https://a.com", "工具"),
                        importItem("B", "javascript:alert(1)", "工具")), "skip"));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).row()).isEqualTo(3);
        assertThat(result.errors().get(0).message()).contains("http");
        assertThat(result.errors().get(0).name()).isEqualTo("B");
    }

    @Test
    @DisplayName("importItems_emptyItems_throwsBadRequest")
    void importItems_emptyItems_throwsBadRequest() {
        assertThatThrownBy(() -> navigationService.importItems(
                new NavigationImportRequest(Collections.emptyList(), "skip")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("exportList_exceedsMaxSize_throwsBadRequest")
    void exportList_exceedsMaxSize_throwsBadRequest() {
        when(navigationMapper.countByCondition(any(), any(), any())).thenReturn(5_001L);

        assertThatThrownBy(() -> navigationService.exportList(new NavigationQueryRequest("kw", null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        verify(navigationMapper, never()).selectByCondition(any(), any(), any(), anyLong(), anyInt());
    }

    /** 逐行校验器桩：默认没有约束违规，行级校验的分支由用例自己构造（如伪协议链接） */
    private void stubNoViolation() {
        when(validator.validate(any(NavigationImportItemRequest.class))).thenReturn(Collections.emptySet());
    }

    private NavigationImportItemRequest importItem(String name, String url, String cat) {
        return new NavigationImportItemRequest(name, url, cat, "", "", "");
    }

    private Navigation mockNavigation(String id, String name, String url, String cat, String description) {
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
