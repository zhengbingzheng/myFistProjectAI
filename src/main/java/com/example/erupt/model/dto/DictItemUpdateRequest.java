package com.example.erupt.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典项-编辑请求。
 *
 * <p>同样不含 {@code dictType}：把一项挪到另一个类型下不是"编辑"，
 * 而是"删除 + 新建"，语义不同就不该塞进同一个接口。</p>
 */
public record DictItemUpdateRequest(

        @NotBlank(message = "显示文本不能为空")
        @Size(max = 100, message = "显示文本不能超过 100 个字符")
        String label,

        @NotBlank(message = "存储值不能为空")
        @Size(max = 100, message = "存储值不能超过 100 个字符")
        String value,

        /** 排序号，升序 */
        Integer sortNum,

        /** 状态：1 启用，0 禁用 */
        @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
        @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
        Integer status,

        /** 前端标签样式：success/warning/danger/info/primary */
        @Size(max = 32, message = "标签样式不能超过 32 个字符")
        String listClass,

        /** 是否默认值：1 是，0 否 */
        @Min(value = 0, message = "是否默认取值只能为 0（否）或 1（是）")
        @Max(value = 1, message = "是否默认取值只能为 0（否）或 1（是）")
        Integer isDefault,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
