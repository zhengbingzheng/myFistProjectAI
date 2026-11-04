package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除入参（知识库 / 导航等模块共用）。
 *
 * <p>语义为「整批通过才执行」：ids 中只要有一个不存在，整批拒绝，不产生部分删除。
 * 删除是不可逆操作，宁可整批失败也不能让调用方拿到说不清的结果。</p>
 *
 * <p>主键统一改为 32 位字符串后，这里从 {@code List<Long>} 调整为 {@code List<String>}；
 * 语义与校验规则都未变化。附件模块原先就用自己的 {@link AttachmentBatchDeleteRequest}，不受影响。</p>
 */
public record BatchDeleteRequest(

        @NotEmpty(message = "待删除的 id 列表不能为空")
        @Size(max = BatchDeleteRequest.MAX_BATCH_DELETE_SIZE,
                message = "单次批量删除不能超过 " + BatchDeleteRequest.MAX_BATCH_DELETE_SIZE + " 条")
        List<@NotNull(message = "id 不能为 null") String> ids
) {

    /**
     * 单次批量删除条数上限：避免一次传入海量 id 导致 IN 条件过长、删除锁范围过大。
     */
    public static final int MAX_BATCH_DELETE_SIZE = 500;
}
