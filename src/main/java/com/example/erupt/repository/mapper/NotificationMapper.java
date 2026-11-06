package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 站内通知的数据访问。
 *
 * <p>通知只有接收人一个视角：所有查询都带 userId 条件，归属校验在 SQL 层完成，
 * 不存在「管理员代查他人通知」的入口（需要时走审计/查库，不走业务接口）。</p>
 */
@Mapper
public interface NotificationMapper {

    int insert(Notification notification);

    /** 某用户的通知分页，未读在前、同状态按时间倒序——「先处理没读的」是收件箱的通用预期 */
    List<Notification> selectPageByUser(@Param("userId") String userId,
                                        @Param("type") String type,
                                        @Param("readFlag") String readFlag,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    long countByUser(@Param("userId") String userId,
                     @Param("type") String type,
                     @Param("readFlag") String readFlag);

    /** 未读计数：铃铛角标，走 (user_id, read_flag) 联合索引 */
    long countUnread(@Param("userId") String userId);

    Notification selectByIdAndUser(@Param("id") String id, @Param("userId") String userId);

    int markRead(@Param("id") String id, @Param("userId") String userId);

    /** 全部已读：只更新未读的行，已读行不动（保留各自的真实已读时间） */
    int markAllRead(@Param("userId") String userId);

    /** 软删除：用户删掉一条通知只是列表里不可见 */
    int logicDelete(@Param("id") String id, @Param("userId") String userId);

    /**
     * 事件去重：同一业务对象（biz_id + type）在 withinDays 天内是否已发过通知。
     * 任务提醒的「3 天内提醒过就不再发」靠它判断，走 (biz_id, type) 索引。
     */
    boolean existsRecent(@Param("bizId") String bizId,
                         @Param("type") String type,
                         @Param("withinDays") int withinDays);
}
