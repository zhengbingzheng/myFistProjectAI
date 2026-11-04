package com.example.erupt.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典类型-编辑请求。
 *
 * <p>刻意不含 {@code dictType}：其它模块可能已经按这个编码写死了引用，
 * 一旦允许改名，调用方会集体取不到数据。真要换编码，只能删掉重建。</p>
 */
public record DictTypeUpdateRequest(

        @NotBlank(message = "字典类型名称不能为空")
        @Size(max = 100, message = "字典类型名称不能超过 100 个字符")
        String dictName,

        /** 状态：1 启用，0 禁用 */
        @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
        @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
