package com.example.erupt.model.vo;

/**
 * 智能体消息里的附件元信息。
 *
 * <p>只带「引用 + 展示所需」的字段，文件本体仍归附件模块（{@code t_attachment}）管理，
 * 前端拿 {@code id} 走既有预览接口取流。</p>
 *
 * @param id          附件 ID，业务关联 t_attachment.id
 * @param name        原始文件名
 * @param contentType MIME 类型
 * @param size        字节数
 * @param parsed      {@code true} 表示正文已被读入模型上下文（仅文本类附件）；
 *                    {@code false} 表示只存档、模型看不到内容（图片 / PDF / Office 等）
 */
public record AgentAttachmentVO(String id, String name, String contentType, Long size, boolean parsed) {
}
