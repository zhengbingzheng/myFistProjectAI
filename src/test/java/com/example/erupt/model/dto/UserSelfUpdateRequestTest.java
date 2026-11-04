package com.example.erupt.model.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自助更新入参的反序列化契约。
 *
 * <p>守护的是一个兼容性承诺：老前端可能沿用管理端表单，把 {@code status} / {@code remark}
 * 一起提交给 {@code PUT /api/users/me}。拆分 DTO 之前这些字段由服务层忽略；
 * 拆分之后必须仍然是「静默丢弃」，而不是因为未知字段变成 400 把老前端打挂。</p>
 */
class UserSelfUpdateRequestTest {

    /**
     * 故意打开 FAIL_ON_UNKNOWN_PROPERTIES：不依赖 Spring Boot 的全局默认值，
     * 证明是类上的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 在起作用。
     * 这里用 Jackson 3（tools.jackson），与 Spring MVC 实际使用的版本一致。
     */
    private final JsonMapper strictMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    @DisplayName("请求体带 status / remark 时被静默丢弃，其余字段正常绑定")
    void managementFieldsAreIgnored() {
        String json = """
                {"nickname":"新昵称","email":"a@b.com","status":1,"remark":"我把自己解禁了"}
                """;

        UserSelfUpdateRequest request = strictMapper.readerFor(UserSelfUpdateRequest.class).readValue(json);

        assertThat(request.nickname()).isEqualTo("新昵称");
        assertThat(request.email()).isEqualTo("a@b.com");
        assertThat(request.avatar()).isNull();
        assertThat(request.phone()).isNull();
    }

    @Test
    @DisplayName("类型上不存在 status / remark 访问器——管理字段在编译期就不可达")
    void typeHasNoManagementAccessors() {
        assertThat(UserSelfUpdateRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("nickname", "avatar", "email", "phone");
    }
}
