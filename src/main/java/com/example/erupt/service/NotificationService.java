package com.example.erupt.service;

import com.example.erupt.model.vo.NotificationVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 站内通知服务：事件源写通知 + 实时推送，接收人读自己的收件箱。
 *
 * <p>事件源（智能体回复完成、任务提醒、系统公告）调用 {@link #notify}，
 * 由本服务负责「落库 → 未读数回查 → WebSocket 推送」的完整链路；
 * 推送复用即时通信的 {@code ChatSessionRegistry}（同一个 /ws/chat 连接，按 type 分帧），
 * 不开第二条连接。</p>
 */
public interface NotificationService {

    /**
     * 给一个用户发一条通知：落库并在事务提交后推送 WS 帧。
     *
     * <p>挂 {@code Transactional} 是为了与调用方的事务合并——事件源多半在事务里
     * （先写业务表再发通知），合并后推送自然落在业务提交之后，不会出现
     * 「通知先到、数据还没提交」的时序错乱。</p>
     *
     * @param userId  接收人 t_user.id
     * @param title   标题（一句话说清发生了什么）
     * @param content 正文补充，可空
     * @param type    类型：agent / task / system
     * @param bizPath 前端路由，可空
     */
    @Transactional(rollbackFor = Exception.class)
    void notify(String userId, String title, String content, String type, String bizPath);

    /**
     * 带 bizId 的重载：需要「按业务对象去重 / 溯源」的事件源使用（如任务提醒）。
     *
     * <p>bizId 是业务对象 id（如甘特任务 id），定时任务按「biz_id + type」
     * 反查最近是否已提醒过，避免每天对同一个任务重复轰炸。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    void notify(String userId, String title, String content, String type, String bizPath, String bizId);

    /** 某用户的通知分页（type / readFlag 可空 = 全部） */
    Map<String, Object> list(String userId, String type, String readFlag, int page, int pageSize);

    /** 未读数：铃铛角标 */
    long unreadCount(String userId);

    /** 标记单条已读（归属不符按 NOT_FOUND 处理，避免探测他人通知 id） */
    void markRead(String userId, String id);

    /** 全部已读 */
    int markAllRead(String userId);

    /** 软删除单条（归属不符按 NOT_FOUND 处理） */
    void delete(String userId, String id);

    /** VO 转换 */
    List<NotificationVO> toVOs(List<?> entities);
}
