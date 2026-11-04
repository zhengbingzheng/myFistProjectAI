package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.OperationLogQueryRequest;
import com.example.erupt.repository.entity.OperationLog;
import com.example.erupt.repository.mapper.OperationLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogServiceImplTest {

    @Mock
    private OperationLogMapper operationLogMapper;

    @InjectMocks
    private OperationLogServiceImpl operationLogService;

    @Test
    @DisplayName("page_关键词做LIKE转义并下推到mapper")
    void page_escapesKeyword() {
        when(operationLogMapper.countByCondition(any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(operationLogMapper.selectByCondition(any(), any(), any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(new OperationLog()));

        operationLogService.page(new OperationLogQueryRequest(
                "100%_user", null, null, null, null, null, 1, 10));

        // % 与 _ 必须先转义，否则用户输入会被当成 LIKE 通配符
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(operationLogMapper).countByCondition(keyword.capture(), any(), any(), any(), any(), any());
        assertThat(keyword.getValue()).isEqualTo("100/%/_user");
    }

    @Test
    @DisplayName("page_空结果返回空页")
    void page_emptyResult() {
        when(operationLogMapper.countByCondition(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        PageResult<OperationLog> result = operationLogService.page(new OperationLogQueryRequest(
                null, "users", "delete", 0, null, null, 1, 10));

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("page_深分页拒绝400")
    void page_deepOffsetRejected() {
        assertThatThrownBy(() -> operationLogService.page(new OperationLogQueryRequest(
                null, null, null, null, null, null, 200_000, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("record_投递不抛异常（异步旁路）")
    void record_neverThrows() {
        assertThatCode(() -> operationLogService.record(new OperationLog()))
                .doesNotThrowAnyException();
    }
}
