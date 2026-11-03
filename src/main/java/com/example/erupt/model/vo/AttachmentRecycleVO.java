package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 回收站附件视图对象。
 *
 * <p>比 {@link AttachmentVO} 多出两个字段，这两个字段正是回收站界面要展示的核心信息：
 * 什么时候被删的、什么时候会被自动清理掉。正常列表不需要它们，故单独建模而非往 VO 里塞 null。</p>
 *
 * @param id                附件标识
 * @param originalName      原始文件名
 * @param fileExt           扩展名
 * @param fileType          分类：image/document/video/audio/archive/other
 * @param contentType       标准媒体类型
 * @param fileSize          文件大小，单位字节
 * @param storageType       存储方式
 * @param downloadCount     下载次数
 * @param createTime        上传时间
 * @param deleteTime        移入回收站的时间，回收站列表默认按此倒序
 * @param recycleExpireTime 回收站到期时间，到期后由清理任务彻底删除
 */
public record AttachmentRecycleVO(String id,
                                  String originalName,
                                  String fileExt,
                                  String fileType,
                                  String contentType,
                                  Long fileSize,
                                  String storageType,
                                  Long downloadCount,
                                  LocalDateTime createTime,
                                  LocalDateTime deleteTime,
                                  LocalDateTime recycleExpireTime) {
}
