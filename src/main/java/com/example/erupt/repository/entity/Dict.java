package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据字典-类型实体，对应表 {@code t_dict}。
 *
 * <p>一个"字典类型"是一组选项的容器（如 用户状态），具体选项在 {@link DictItem} 中，
 * 两表通过 {@code dictType} 业务字段关联，不加外键——与 {@code t_role_menu} 的约定一致，
 * 避免删类型时被外键约束卡住，也方便按编码直接查项。</p>
 *
 * <p>{@code dictType} 建表时做了"仅未删除记录唯一"（生成列 {@code dict_type_active}），
 * 所以一个编码被软删除后可以重新使用，但同一时刻不能有两个生效的同名编码。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dict {

    private String id;
    /** 字典类型编码，如 sys_user_status */
    private String dictType;
    /** 字典类型名称，如 用户状态 */
    private String dictName;
    /** 状态：1 启用，0 禁用 */
    private Integer status;
    private String remark;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
