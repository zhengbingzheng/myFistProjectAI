package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.KnowledgeCreateRequest;
import com.example.erupt.model.dto.KnowledgeQueryRequest;
import com.example.erupt.model.dto.KnowledgeUpdateRequest;
import com.example.erupt.model.vo.KnowledgeVO;
import com.example.erupt.repository.entity.Knowledge;
import com.example.erupt.repository.mapper.KnowledgeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceImplTest {

    @Mock
    private KnowledgeMapper knowledgeMapper;

    @InjectMocks
    private KnowledgeServiceImpl knowledgeService;

    @Test
    @DisplayName("create_duplicatedNameInType_throwsConflictException")
    void create_duplicatedNameInType_throwsConflictException() {
        when(knowledgeMapper.selectByTypeAndName("java", "如何重置密码"))
                .thenReturn(mockKnowledge(1L, "java", "如何重置密码"));

        assertThatThrownBy(() -> knowledgeService.create(new KnowledgeCreateRequest("如何重置密码", "<p>正文</p>", "java")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.CONFLICT);

        verify(knowledgeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("create_validRequest_returnsSuccess")
    void create_validRequest_returnsSuccess() {
        when(knowledgeMapper.selectByTypeAndName("java", "如何重置密码")).thenReturn(null);
        when(knowledgeMapper.insert(any(Knowledge.class))).thenAnswer(invocation -> {
            Knowledge knowledge = invocation.getArgument(0);
            knowledge.setId(100L);
            return 1;
        });

        Result<Void> result = knowledgeService.create(new KnowledgeCreateRequest("如何重置密码", "<p>正文</p>", "java"));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("新增成功");

        ArgumentCaptor<Knowledge> captor = ArgumentCaptor.forClass(Knowledge.class);
        verify(knowledgeMapper).insert(captor.capture());
        assertThat(captor.getValue().getKnowledgeName()).isEqualTo("如何重置密码");
        assertThat(captor.getValue().getKnowledgeType()).isEqualTo("java");
        assertThat(captor.getValue().getKnowledgeContent()).isEqualTo("<p>正文</p>");
        assertThat(captor.getValue().getCreateTime()).isNotNull();
    }

    @Test
    @DisplayName("create_affectedRowsZero_returnsFailure")
    void create_affectedRowsZero_returnsFailure() {
        when(knowledgeMapper.selectByTypeAndName("java", "如何重置密码")).thenReturn(null);
        when(knowledgeMapper.insert(any(Knowledge.class))).thenReturn(0);

        Result<Void> result = knowledgeService.create(new KnowledgeCreateRequest("如何重置密码", "<p>正文</p>", "java"));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("新增失败");
        verify(knowledgeMapper).insert(any(Knowledge.class));
    }

    @Test
    @DisplayName("getById_knowledgeNotExists_throwsNotFoundException")
    void getById_knowledgeNotExists_throwsNotFoundException() {
        when(knowledgeMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> knowledgeService.getById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("update_partialFields_keepsUntouchedValues")
    void update_partialFields_keepsUntouchedValues() {
        Knowledge existing = mockKnowledge(1L, "java", "如何重置密码");
        existing.setKnowledgeContent("<p>旧正文</p>");
        when(knowledgeMapper.selectById(1L)).thenReturn(existing);
        when(knowledgeMapper.updateById(existing)).thenReturn(1);

        Result<Void> result = knowledgeService.update(1L, new KnowledgeUpdateRequest(null, null, "erupt"));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("更新成功");
        assertThat(existing.getKnowledgeType()).isEqualTo("erupt");
        assertThat(existing.getKnowledgeContent()).isEqualTo("<p>旧正文</p>");
        verify(knowledgeMapper).updateById(existing);
    }

    @Test
    @DisplayName("update_affectedRowsZero_returnsFailure")
    void update_affectedRowsZero_returnsFailure() {
        Knowledge existing = mockKnowledge(1L, "java", "如何重置密码");
        when(knowledgeMapper.selectById(1L)).thenReturn(existing);
        when(knowledgeMapper.updateById(existing)).thenReturn(0);

        Result<Void> result = knowledgeService.update(1L, new KnowledgeUpdateRequest(null, null, "erupt"));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("更新失败");
        verify(knowledgeMapper).updateById(existing);
    }

    @Test
    @DisplayName("update_nameDuplicatedWithOtherRecord_throwsConflictException")
    void update_nameDuplicatedWithOtherRecord_throwsConflictException() {
        Knowledge existing = mockKnowledge(1L, "java", "如何重置密码");
        when(knowledgeMapper.selectById(1L)).thenReturn(existing);
        when(knowledgeMapper.selectByTypeAndName("java", "如何找回账号"))
                .thenReturn(mockKnowledge(2L, "java", "如何找回账号"));

        assertThatThrownBy(() -> knowledgeService.update(1L, new KnowledgeUpdateRequest("如何找回账号", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.CONFLICT);

        verify(knowledgeMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("delete_notFound_throwsNotFoundException")
    void delete_notFound_throwsNotFoundException() {
        when(knowledgeMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> knowledgeService.delete(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(knowledgeMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete_existing_returnsSuccess")
    void delete_existing_returnsSuccess() {
        when(knowledgeMapper.selectById(1L)).thenReturn(mockKnowledge(1L, "java", "如何重置密码"));
        when(knowledgeMapper.deleteById(1L)).thenReturn(1);

        Result<Void> result = knowledgeService.delete(1L);

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("删除成功");
        verify(knowledgeMapper).deleteById(1L);
    }

    @Test
    @DisplayName("delete_affectedRowsZero_returnsFailure")
    void delete_affectedRowsZero_returnsFailure() {
        when(knowledgeMapper.selectById(1L)).thenReturn(mockKnowledge(1L, "java", "如何重置密码"));
        when(knowledgeMapper.deleteById(1L)).thenReturn(0);

        Result<Void> result = knowledgeService.delete(1L);

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("删除失败");
        verify(knowledgeMapper).deleteById(1L);
    }

    @Test
    @DisplayName("page_emptyResult_returnsEmptyItems")
    void page_emptyResult_returnsEmptyItems() {
        when(knowledgeMapper.countByCondition("重置", "java")).thenReturn(0L);

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("重置", "java", 1, 10));

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        verify(knowledgeMapper, never()).selectByCondition(any(), any(), anyLong(), eq(10));
    }

    @Test
    @DisplayName("page_hasResult_returnsItems")
    void page_hasResult_returnsItems() {
        when(knowledgeMapper.countByCondition("重置", "java")).thenReturn(1L);
        when(knowledgeMapper.selectByCondition("重置", "java", 0L, 10))
                .thenReturn(List.of(mockKnowledge(1L, "java", "如何重置密码")));

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("重置", "java", 1, 10));

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("listTypes_returnsDistinctTypes_trimAndDropBlank")
    void listTypes_returnsDistinctTypes_trimAndDropBlank() {
        when(knowledgeMapper.selectDistinctTypes())
                .thenReturn(Arrays.asList("java", " java ", "erupt", " ", null, "Mac", "Mac"));

        List<String> types = knowledgeService.listTypes();

        assertThat(types).containsExactly("java", "erupt", "Mac");
        verify(knowledgeMapper).selectDistinctTypes();
    }

    @Test
    @DisplayName("listTypes_emptyResult_returnsEmptyList")
    void listTypes_emptyResult_returnsEmptyList() {
        when(knowledgeMapper.selectDistinctTypes()).thenReturn(Collections.emptyList());

        assertThat(knowledgeService.listTypes()).isEmpty();
    }

    @Test
    @DisplayName("listTypes_nullResult_returnsEmptyList")
    void listTypes_nullResult_returnsEmptyList() {
        when(knowledgeMapper.selectDistinctTypes()).thenReturn(null);

        assertThat(knowledgeService.listTypes()).isEmpty();
    }

    @Test
    @DisplayName("listTypes_allBlank_returnsEmptyList")
    void listTypes_allBlank_returnsEmptyList() {
        when(knowledgeMapper.selectDistinctTypes()).thenReturn(Arrays.asList("", "   ", null));

        assertThat(knowledgeService.listTypes()).isEmpty();
    }

    @Test
    @DisplayName("page_fullTextEnabled_multiCharKeyword_usesFullTextQuery")
    void page_fullTextEnabled_multiCharKeyword_usesFullTextQuery() {
        ReflectionTestUtils.setField(knowledgeService, "fulltextEnabled", true);
        when(knowledgeMapper.countByConditionFullText("根本原因", null)).thenReturn(1L);
        when(knowledgeMapper.selectByConditionFullText("根本原因", null, 0L, 10))
                .thenReturn(List.of(mockKnowledge(6L, "erupt", "列表表头搜索")));

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("根本原因", null, 1, 10));

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        verify(knowledgeMapper).countByConditionFullText("根本原因", null);
        verify(knowledgeMapper, never()).countByCondition(any(), any());
    }

    @Test
    @DisplayName("page_fullTextEnabled_singleCharKeyword_fallsBackToLike")
    void page_fullTextEnabled_singleCharKeyword_fallsBackToLike() {
        ReflectionTestUtils.setField(knowledgeService, "fulltextEnabled", true);
        // ngram_token_size=2，单字命中不了全文索引，应降级为 LIKE，保证结果不退化
        when(knowledgeMapper.countByCondition("A", null)).thenReturn(2L);
        when(knowledgeMapper.selectByCondition("A", null, 0L, 10))
                .thenReturn(List.of(mockKnowledge(1L, "erupt", "A 标题")));

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("A", null, 1, 10));

        assertThat(page.total()).isEqualTo(2L);
        verify(knowledgeMapper).countByCondition("A", null);
        verify(knowledgeMapper, never()).countByConditionFullText(any(), any());
    }

    @Test
    @DisplayName("page_fullTextEnabled_operatorsStrippedBeforeMatch")
    void page_fullTextEnabled_operatorsStrippedBeforeMatch() {
        ReflectionTestUtils.setField(knowledgeService, "fulltextEnabled", true);
        // BOOLEAN MODE 操作符需剔除，避免 "C++"、"@Erupt" 被解析成必须/排除语义
        when(knowledgeMapper.countByConditionFullText("Erupt", null)).thenReturn(3L);
        when(knowledgeMapper.selectByConditionFullText("Erupt", null, 0L, 10))
                .thenReturn(List.of(mockKnowledge(7L, "erupt", "倒序")));

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("@Erupt", null, 1, 10));

        assertThat(page.total()).isEqualTo(3L);
        verify(knowledgeMapper).countByConditionFullText("Erupt", null);
    }

    @Test
    @DisplayName("page_fullTextEnabled_operatorOnlyKeyword_fallsBackToLike")
    void page_fullTextEnabled_operatorOnlyKeyword_fallsBackToLike() {
        ReflectionTestUtils.setField(knowledgeService, "fulltextEnabled", true);
        // "C++" 清洗后只剩单字 "C"，不足 ngram 最小长度，降级为 LIKE 且保留原始关键词
        when(knowledgeMapper.countByCondition("C++", null)).thenReturn(1L);
        when(knowledgeMapper.selectByCondition("C++", null, 0L, 10))
                .thenReturn(List.of(mockKnowledge(2L, "erupt", "C++ 笔记")));

        PageResult<KnowledgeVO> page = knowledgeService.page(new KnowledgeQueryRequest("C++", null, 1, 10));

        assertThat(page.total()).isEqualTo(1L);
        verify(knowledgeMapper).countByCondition("C++", null);
        verify(knowledgeMapper, never()).countByConditionFullText(any(), any());
    }

    @Test
    @DisplayName("deleteBatch_allIdsExist_returnsSuccess")
    void deleteBatch_allIdsExist_returnsSuccess() {
        when(knowledgeMapper.countByIds(List.of(1L, 2L))).thenReturn(2L);
        when(knowledgeMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        Result<Void> result = knowledgeService.deleteBatch(List.of(1L, 2L));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("批量删除成功，共删除 2 条");
        verify(knowledgeMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete")
    void deleteBatch_containsMissingId_throwsNotFoundAndSkipsDelete() {
        when(knowledgeMapper.countByIds(List.of(1L, 999L))).thenReturn(1L);

        assertThatThrownBy(() -> knowledgeService.deleteBatch(List.of(1L, 999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(knowledgeMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteBatch_emptyIds_throwsBadRequest")
    void deleteBatch_emptyIds_throwsBadRequest() {
        assertThatThrownBy(() -> knowledgeService.deleteBatch(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(knowledgeMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteBatch_duplicateIds_deduplicatesBeforeDelete")
    void deleteBatch_duplicateIds_deduplicatesBeforeDelete() {
        when(knowledgeMapper.countByIds(List.of(1L, 2L))).thenReturn(2L);
        when(knowledgeMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        knowledgeService.deleteBatch(List.of(1L, 2L, 1L, 2L));

        verify(knowledgeMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("deleteBatch_affectedRowsZero_returnsFailure")
    void deleteBatch_affectedRowsZero_returnsFailure() {
        when(knowledgeMapper.countByIds(List.of(1L))).thenReturn(1L);
        when(knowledgeMapper.deleteByIds(List.of(1L))).thenReturn(0);

        Result<Void> result = knowledgeService.deleteBatch(List.of(1L));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("批量删除失败");
    }

    private Knowledge mockKnowledge(Long id, String type, String name) {
        Knowledge knowledge = new Knowledge();
        knowledge.setId(id);
        knowledge.setKnowledgeType(type);
        knowledge.setKnowledgeName(name);
        return knowledge;
    }
}
