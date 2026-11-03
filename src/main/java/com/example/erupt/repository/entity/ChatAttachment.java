package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天附件实体，对应表 {@code t_chat_attachment}。
 *
 * <h3>为什么不用 {@code t_attachment}</h3>
 *
 * <p>文件管理（文件列表）与聊天是两套互不相干的业务：前者是"我上传的文件仓库"，
 * 按菜单权限授权、支持回收站与批量打包；后者是消息的一部分，任何登录用户都要用，
 * 随消息一起读。若共用一张表，聊天里发的每张图都会出现在文件列表里，
 * 文件列表的清空/删除也会顺手带走聊天记录里的图——这正是要避免的相互影响。</p>
 *
 * <p>因此这里独立建表、独立存储目录，两张表之间没有任何外键或业务耦合。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {

    private String id;
    /** 原始文件名（展示与下载时还原） */
    private String originalName;
    /** 扩展名（小写、不含点） */
    private String fileExt;
    /** MIME 类型，前端据此决定按图片渲染还是按文件卡片展示 */
    private String contentType;
    /** 字节数 */
    private Long fileSize;
    /** 相对存储根目录的路径，形如 2026/10/22/xxxx.png */
    private String storagePath;
    /** 上传者用户ID：只有本人能把它作为消息发出去 */
    private String uploaderId;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private LocalDateTime createTime;
}
