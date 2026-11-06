package com.example.erupt.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamEndpointPolicyTest {

    private final UpstreamEndpointPolicy policy = new UpstreamEndpointPolicy("open.bigmodel.cn");

    @Test
    @DisplayName("允许名单里的 https 主机可以通过")
    void allowsListedHttpsHost() {
        URI uri = policy.require("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(uri.getHost()).isEqualTo("open.bigmodel.cn");
    }

    @Test
    @DisplayName("http、其它主机、带用户信息的地址拒绝")
    void rejectsUnexpectedTargets() {
        assertThatThrownBy(() -> policy.require("http://open.bigmodel.cn/v1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.require("https://127.0.0.1/latest/meta-data"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.require("https://evil.example/v1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.require("https://user:secret@open.bigmodel.cn/v1"))
                .isInstanceOf(BusinessException.class);
    }
}
