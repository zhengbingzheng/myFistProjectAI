package com.example.erupt.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典项-新建请求。
 *
 * <p>{@code dictType} 必须指向一个已存在的字典类型，由服务层校验——
 * 参数校验只能保证"不为空"，保证不了"指向的实体存在"。</p>
 */
public record DictItemRequest(

        @NotBlank(message = "所属字典类型不能为空")
        @Size(max = 64, message = "字典类型编码不能超过 64 个字符")
        String dictType,

        @NotBlank(message = "显示文本不能为空")
        @Size(max = 100, message = "显示文本不能超过 100 个字符")
        String label,

        @NotBlank(message = "存储值不能为空")
        @Size(max = 100, message = "存储值不能超过 100 个字符")
        String value,

        /** 排序号，升序；不传按 0 处理 */
        Integer sortNum,

        /** 状态：1 启用，0 禁用；不传按启用处理 */
        @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
        @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
        Integer status,

        /** 前端标签样式：success/warning/danger/info/primary */
        @Size(max = 32, message = "标签样式不能超过 32 个字符")
        String listClass,

        /** 是否默认值：1 是，0 否；不传按否处理 */
        @Min(value = 0, message = "是否默认取值只能为 0（否）或 1（是）")
        @Max(value = 1, message = "是否默认取值只能为 0（否）或 1（是）")
        Integer isDefault,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
