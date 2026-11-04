package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件附件实体（数据层），与库表 {@code t_attachment} 一一对应。
 *
 * <p>主键 {@code id} 为字符串（32 位随机串），由应用层生成，不使用数据库自增。</p>
 *
 * <p>{@code delFlag} 决定文件处于正常状态还是回收站；只有在回收站再次删除时
 * 才物理删除记录并清理存储介质上的文件。</p>
 *
 * <p>getter/setter 等样板代码由 Lombok 在编译期生成。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    /** 主键，字符串（32 位 UUID 去横线） */
    private String id;
    /** 原始文件名（含扩展名） */
    private String originalName;
    /** 扩展名，如 pdf */
    private String fileExt;
    /** 分类：image/document/video/audio/archive/other */
    private String fileType;
    /** 标准媒体类型 */
    private String contentType;
    /** 文件大小，单位字节 */
    private Long fileSize;
    /** 内容 SHA-256 摘要 */
    private String fileHash;
    /** 存储方式：local/oss/db */
    private String storageType;
    /** 相对存储根目录的路径 */
    private String storagePath;
    /** 缩略图相对路径（预留） */
    private String thumbnailPath;
    /** 下载次数 */
    private Long downloadCount;
    /** 备注 */
    private String remark;
    /** 创建人（上传人） */
    private String createBy;
    /** 创建时间（上传时间） */
    private LocalDateTime createTime;
    /** 更新人 */
    private String updateBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
    /** 删除标记：0 正常，1 已进回收站 */
    private String delFlag;
    /** 删除操作人 */
    private String deleteBy;
    /** 移入回收站的时间 */
    private LocalDateTime deleteTime;
    /** 回收站保留到期时间 */
    private LocalDateTime recycleExpireTime;
}
