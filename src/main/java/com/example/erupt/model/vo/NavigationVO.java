package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 导航视图对象（视图层出参模型），不直接暴露数据层实体的列名结构。
 *
 * <p>{@code createUserName} 是给展示用的创建人账号（由列表查询 LEFT JOIN {@code t_user} 带出）。
 * 列表要显示"谁录的这条"，只给 {@code createUserId} 是一串随机字符，前端没法直接呈现；
 * 同时保留 {@code createUserId}，便于需要按人过滤或追溯时使用。</p>
 */
public record NavigationVO(String id, String name, String url, String img, String cat, String description, String tag,
                           String createUserId, String createUserName, String updateUserId,
                           LocalDateTime createTime, LocalDateTime updateTime) {
}
