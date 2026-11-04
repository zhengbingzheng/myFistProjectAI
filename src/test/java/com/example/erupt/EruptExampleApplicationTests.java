package com.example.erupt;

import com.example.erupt.repository.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文冒烟测试：验证配置绑定、MyBatis Mapper 装配与 XML 映射加载是否正常。
 */
@SpringBootTest
class EruptExampleApplicationTests {

    @Autowired
    private UserMapper userMapper;

    @Test
    void contextLoads_mapperIsAvailable() {
        assertThat(userMapper).isNotNull();
    }
}
