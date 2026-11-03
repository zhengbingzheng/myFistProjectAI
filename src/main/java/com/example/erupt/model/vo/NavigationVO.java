package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 导航视图对象（视图层出参模型），不直接暴露数据层实体的列名结构。
 */
public record NavigationVO(Long id, String name, String url, String img, String cat, String description, String tag,
                           Long createUserId, Long updateUserId, LocalDateTime createTime, LocalDateTime updateTime) {
}
