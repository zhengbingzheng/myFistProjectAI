package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多附件打包下载入参。
 *
 * <p>它也是 t_attachment 的字符串主键；服务层会在打包前先校验存在性与总大小阈值。</p>
 */
public record BatchDownloadRequest(

        @NotEmpty(message = "待下载的 id 列表不能为空")
        @Size(max = BatchDownloadRequest.MAX_BATCH_DOWNLOAD_SIZE,
                message = "单次批量下载不能超过 " + BatchDownloadRequest.MAX_BATCH_DOWNLOAD_SIZE + " 个")
        List<@NotNull(message = "id 不能为 null") String> ids
) {

    /**
     * 单次打包下载的数量上限：ZIP 打包是流式串行处理，数量过多会长时间占用连接，也能避免一次请求拖垮 IO。
     */
    public static final int MAX_BATCH_DOWNLOAD_SIZE = 500;
}
