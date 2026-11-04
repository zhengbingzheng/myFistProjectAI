package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 导入文件中的单行数据（视图层入参模型）。
 *
 * <p>字段顺序即 Excel 模板的列顺序：名称、链接地址、分类、图标地址、标签、描述。
 * 约束与前端导入校验保持一致（前端做第一道拦截，这里做最终拦截），
 * 避免「前端放过、后端拒绝」时用户拿到一条说不清的 400。</p>
 *
 * <p>注意：这里的约束由服务层通过 {@code Validator} 逐行触发，
 * 而不是 Controller 的 {@code @Valid}——后者一旦命中就整批拒绝，
 * 拿不到「第几行错在哪」的结果反馈。</p>
 *
 * @param name        名称，对应 {@code navigation_name}
 * @param url         跳转地址，对应 {@code navigation_url}
 * @param cat         分类，对应 {@code cat}
 * @param img         图标地址，对应 {@code navigation_img}
 * @param tag         标签，对应 {@code tag}
 * @param description 描述，对应 {@code desc}
 */
public record NavigationImportItemRequest(

        @NotBlank(message = "名称不能为空")
        @Size(max = 50, message = "名称不能超过 50 个字符")
        String name,

        @NotBlank(message = "链接地址不能为空")
        @Size(max = 255, message = "链接地址不能超过 255 个字符")
        String url,

        @NotBlank(message = "分类不能为空")
        @Size(max = 50, message = "分类不能超过 50 个字符")
        String cat,

        @Size(max = 255, message = "图标地址不能超过 255 个字符")
        String img,

        @Size(max = 50, message = "标签不能超过 50 个字符")
        String tag,

        @Size(max = 200, message = "描述不能超过 200 个字符")
        String description
) {
}
