package com.example.erupt.common;

import com.example.erupt.model.vo.DictItemVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretDictsTest {

    @Test
    @DisplayName("没有字典管理权限时去掉 API Key 和 chat_url")
    void redactsCredentials() {
        List<DictItemVO> visible = SecretDicts.redact(List.of(
                item("API Key", "sk-secret"),
                item("chat_url", "https://open.bigmodel.cn/v1"),
                item("default_model", "glm-5.3")));

        assertThat(visible).extracting(DictItemVO::label).containsExactly("default_model");
        assertThat(visible).extracting(DictItemVO::value).containsExactly("glm-5.3");
    }

    private static DictItemVO item(String label, String value) {
        return new DictItemVO(label, "agent_config", label, value, 1, 1, null, 0, null, null);
    }
}
