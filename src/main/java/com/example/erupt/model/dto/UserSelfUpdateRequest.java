package com.example.erupt.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户自助更新个人资料（{@code PUT /api/users/me}）的入参模型。
 *
 * <p><b>为什么不复用 {@link UserUpdateRequest}</b>：那是管理员接口的入参，带着
 * {@code status}（启用/禁用）和 {@code remark}（管理员备注）。共用时只能靠服务层
 * 「记得不去读这两个字段」来隔离权限——这是一条靠自觉维持的防线：将来有人在
 * {@code updateSelf} 里顺手补一个字段的处理，很容易把 {@code status} 一起带进去，
 * 被禁用的用户就能把自己改回启用。拆成独立类型后，自助接口<b>在类型层面就拿不到</b>
 * 管理字段，编译器替我们守住这条线。</p>
 *
 * <p><b>为什么忽略未知字段</b>：老前端可能沿用管理端的表单对象，把 {@code status}、
 * {@code remark} 一起提交上来。显式 {@code ignoreUnknown = true} 让这些字段被静默丢弃，
 * 行为与拆分前一致（以前也是服务端忽略），而不是因为全局 Jackson 配置的差异变成 400。
 * 显式声明也不依赖 Spring Boot 对 {@code FAIL_ON_UNKNOWN_PROPERTIES} 的默认值。</p>
 *
 * <p>校验规则与 {@link UserUpdateRequest} 对应字段保持一致，改动时两处一起改。</p>
 *
 * @param nickname 昵称
 * @param avatar   头像地址
 * @param email    邮箱
 * @param phone    手机号
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSelfUpdateRequest(
        @Size(max = 32, message = "昵称不能超过 32 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址不能超过 512 个字符")
        String avatar,

        @Email(message = "邮箱格式不正确")
        @Size(max = 64, message = "邮箱不能超过 64 个字符")
        String email,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone
) {
}
