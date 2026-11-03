package com.example.erupt.service;

import com.example.erupt.repository.entity.Attachment;

import java.nio.file.Path;

/**
 * 服务层内部使用的「可下载附件」载体：数据库记录 + 校验通过后的真实文件路径。
 *
 * <p>路径已经过校验（必须在存储根目录之内且文件确实存在），控制器只需做响应头与流式写出，
 * 不再重复拼路径，避免出现多处路径解析逻辑。</p>
 *
 * @param attachment 附件记录（提供文件名、媒体类型、大小等元信息）
 * @param path       校验通过的真实文件路径
 */
public record AttachmentResource(Attachment attachment, Path path) {
}
