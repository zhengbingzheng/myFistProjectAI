package com.example.erupt.model.vo;

import com.example.erupt.repository.entity.DictItem;

import java.time.LocalDateTime;

/**
 * 字典项视图对象。
 *
 * <p>{@code label} 与 {@code value} 都原样下发：前端表格直接显示 label，
 * 下拉组件用 value 作为绑定值，二者不可混用。</p>
 */
public record DictItemVO(
        String id,
        String dictType,
        String label,
        String value,
        Integer sortNum,
        Integer status,
        String listClass,
        Integer isDefault,
        String remark,
        LocalDateTime createTime
) {

    public static DictItemVO of(DictItem item) {
        if (item == null) {
            return null;
        }
        return new DictItemVO(
                item.getId(),
                item.getDictType(),
                item.getLabel(),
                item.getValue(),
                item.getSortNum(),
                item.getStatus(),
                item.getListClass(),
                item.getIsDefault(),
                item.getRemark(),
                item.getCreateTime()
        );
    }
}
