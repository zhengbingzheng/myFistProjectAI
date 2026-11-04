package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 聊天附件的数据访问。
 *
 * <p>只有两个动作：写入一条记录、按 id 取一条。聊天附件没有回收站、没有批量打包，
 * 删除随消息一起来（或由运维按时间归档），用不到更复杂的查询。</p>
 */
@Mapper
public interface ChatAttachmentMapper {

    int insert(ChatAttachment attachment);

    /** 按 id 取附件；已删除或不存在返回 null */
    ChatAttachment selectById(@Param("id") String id);
}
