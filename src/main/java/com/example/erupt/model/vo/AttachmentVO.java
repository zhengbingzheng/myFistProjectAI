package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 附件视图对象。
 *
 * <p>上传成功后返回，前端据此拿到 {@code id} 用于后续的下载与删除操作。
 * 出于安全考虑不返回 {@code storagePath}（真实落盘位置不应暴露给前端）。</p>
 *
 * @param id            附件标识，字符串主键，后续下载/删除都用它
 * @param originalName  原始文件名，用于界面展示
 * @param fileExt       扩展名
 * @param fileType      分类：image/document/video/audio/archive/other
 * @param contentType   标准媒体类型
 * @param fileSize      文件大小，单位字节
 * @param storageType   存储方式
 * @param downloadCount 下载次数
 * @param createTime    上传时间
 */
public record AttachmentVO(String id,
                           String originalName,
                           String fileExt,
                           String fileType,
                           String contentType,
                           Long fileSize,
                           String storageType,
                           Long downloadCount,
                           LocalDateTime createTime) {
}
