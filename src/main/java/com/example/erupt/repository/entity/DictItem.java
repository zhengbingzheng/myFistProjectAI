package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据字典-字典项实体，对应表 {@code t_dict_item}。
 *
 * <p>{@code label} 是给人看的（"启用"），{@code value} 是给程序存的（"1"）——
 * 两者必须分开：一旦把显示文本当存储值，改一次文案就要刷一遍历史数据。</p>
 *
 * <p>{@code sortNum} 控制下拉里的先后顺序；{@code isDefault} 标记默认选中项。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictItem {

    private String id;
    /** 所属字典类型编码，业务关联 t_dict.dict_type */
    private String dictType;
    /** 显示文本 */
    private String label;
    /** 存储值 */
    private String value;
    /** 排序号，升序 */
    private Integer sortNum;
    /** 状态：1 启用，0 禁用 */
    private Integer status;
    /** 前端标签样式：success/warning/danger/info/primary */
    private String listClass;
    /** 是否默认值：1 是，0 否 */
    private Integer isDefault;
    private String remark;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
