package com.example.erupt.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 字典类型-新建请求。
 *
 * <p>{@code dictType} 是其它模块引用字典的唯一凭据，因此限定为字母、数字、下划线：
 * 编码里出现空格或中文时，调用方写死的字符串与库里存的值极易对不上，
 * 而这类问题往往要到线上才暴露。</p>
 */
public record DictTypeRequest(

        @NotBlank(message = "字典类型编码不能为空")
        @Size(max = 64, message = "字典类型编码不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "字典类型编码只能包含字母、数字和下划线")
        String dictType,

        @NotBlank(message = "字典类型名称不能为空")
        @Size(max = 100, message = "字典类型名称不能超过 100 个字符")
        String dictName,

        /** 状态：1 启用，0 禁用；不传按启用处理 */
        @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
        @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
