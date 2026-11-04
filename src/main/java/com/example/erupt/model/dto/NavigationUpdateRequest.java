package com.example.erupt.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新导航请求参数（视图层入参模型），字段均为可选，非空才更新。
 *
 * @param name        名称，对应 {@code navigation_name}
 * @param url         跳转地址，对应 {@code navigation_url}
 * @param img         图标地址，对应 {@code navigation_img}
 * @param cat         分类，对应 {@code cat}
 * @param description 描述，对应 {@code desc}
 * @param tag         标签，对应 {@code tag}
 */
public record NavigationUpdateRequest(
        @Size(max = 255, message = "名称不能超过 255 个字符")
        String name,

        @Size(max = 255, message = "跳转地址不能超过 255 个字符")
        String url,

        @Size(max = 255, message = "图标地址不能超过 255 个字符")
        String img,

        @Size(max = 255, message = "分类不能超过 255 个字符")
        String cat,

        @Size(max = 255, message = "描述不能超过 255 个字符")
        String description,

        @Size(max = 255, message = "标签不能超过 255 个字符")
        String tag
) {
}
