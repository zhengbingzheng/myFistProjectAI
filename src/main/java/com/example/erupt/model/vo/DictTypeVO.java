package com.example.erupt.model.vo;

import com.example.erupt.repository.entity.Dict;

import java.time.LocalDateTime;

/**
 * 字典类型视图对象。
 *
 * <p>不暴露 {@code delFlag} 与 {@code updateBy}：前者恒为 '0'（列表只查未删除），
 * 后者对管理页面无意义。少暴露一个字段，就少一个"前端误用"的可能。</p>
 */
public record DictTypeVO(
        String id,
        String dictType,
        String dictName,
        Integer status,
        String remark,
        String createBy,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public static DictTypeVO of(Dict dict) {
        if (dict == null) {
            return null;
        }
        return new DictTypeVO(
                dict.getId(),
                dict.getDictType(),
                dict.getDictName(),
                dict.getStatus(),
                dict.getRemark(),
                dict.getCreateBy(),
                dict.getCreateTime(),
                dict.getUpdateTime()
        );
    }
}
