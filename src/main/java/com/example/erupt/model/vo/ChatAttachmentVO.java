package com.example.erupt.model.vo;

/**
 * 聊天消息里携带的附件信息。
 *
 * <p>刻意不暴露 {@code storagePath}：存储路径属于服务端内部细节，
 * 前端只需要"能不能读、多大、什么类型"，读文件一律走带鉴权的下载接口。</p>
 *
 * @param id          附件主键
 * @param name        原始文件名（图片消息也保留，便于下载时还原）
 * @param size        字节数
 * @param contentType MIME 类型，前端据此决定按图片渲染还是按文件展示
 */
public record ChatAttachmentVO(
        String id,
        String name,
        Long size,
        String contentType
) {
}
