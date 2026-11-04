package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量恢复入参。
 *
 * <p>语义为「整批通过才执行」：ids 中只要有一个不存在或不处于回收站，
 * 整批拒绝，不产生部分恢复。这样调用方拿到的结果非成即败，无需再判断哪些成功了。</p>
 */
public record BatchRestoreRequest(

        @NotEmpty(message = "待恢复的 id 列表不能为空")
        @Size(max = BatchRestoreRequest.MAX_BATCH_RESTORE_SIZE,
                message = "单次批量恢复不能超过 " + BatchRestoreRequest.MAX_BATCH_RESTORE_SIZE + " 条")
        List<@NotNull(message = "id 不能为 null") String> ids
) {

    /**
     * 单次批量恢复条数上限：避免一次传入海量 id 导致 IN 条件过长、UPDATE 锁范围过大。
     */
    public static final int MAX_BATCH_RESTORE_SIZE = 500;
}
