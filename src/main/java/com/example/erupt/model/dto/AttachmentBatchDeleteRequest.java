package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 附件批量删除入参（移入回收站 / 彻底删除共用）。
 *
 * <p>与 {@link BatchRestoreRequest} 保持同一套语义：「整批通过才执行」——
 * ids 中只要有一个不存在或状态不对，整批拒绝，不产生部分删除。
 * 删除是不可逆操作，宁可整批失败也不能让调用方拿到说不清的结果。</p>
 *
 * <p>没有直接复用 {@link BatchDeleteRequest} 是因为它的主键是 Long，
 * 而附件主键是 32 位字符串；两者的业务模块也不同。</p>
 */
public record AttachmentBatchDeleteRequest(

        @NotEmpty(message = "待删除的 id 列表不能为空")
        @Size(max = AttachmentBatchDeleteRequest.MAX_BATCH_DELETE_SIZE,
                message = "单次批量删除不能超过 " + AttachmentBatchDeleteRequest.MAX_BATCH_DELETE_SIZE + " 条")
        List<@NotNull(message = "id 不能为 null") String> ids
) {

    /**
     * 单次批量删除条数上限：删除要同时更新一批状态并清理一批磁盘文件，
     * 数量过大会让事务与 IO 长时间被占用。
     */
    public static final int MAX_BATCH_DELETE_SIZE = 500;
}
