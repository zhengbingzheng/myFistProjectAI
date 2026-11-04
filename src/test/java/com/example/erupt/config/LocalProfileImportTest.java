package com.example.erupt.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地凭据装配冒烟测试。
 *
 * <p>守护两点：</p>
 * <ol>
 *   <li>local 档凭据来自 <b>工程根目录 config/application-local.yml</b>
 *       （经 spring.config.import 的 optional 引入）——文件曾误放在
 *       src/main/resources/application-local.yml，会导致真实密码打进 jar 随产物分发；</li>
 *   <li>签名密钥走生产守卫不阻断本地：AuthSecretGuard 必须把 local/dev
 *       视为允许弱默认值的档位，否则零配置启动直接失败。</li>
 * </ol>
 */
@SpringBootTest
class LocalProfileImportTest {

    @Autowired
    private Environment environment;

    @Test
    void localCredentialsAreImportedFromProjectRoot() {
        assertThat(environment.getProperty("spring.config.import"))
                .as("spring.config.import 应指向根目录 local 凭据文件")
                .contains("config/application-local.yml");
        // local 凭据被真正装载（本机开发依赖这一行；生产若无文件则此断言只在 local 生效）
        assertThat(environment.getProperty("spring.datasource.password"))
                .as("local 档下 DB_PASSWORD 应回填本地凭据文件中的值")
                .isNotBlank();
    }

    @Test
    void authSecretGuardToleratesLocalProfile() {
        // application.yml 的 profiles.default=local，零配置启动必须被守卫放行
        assertThat(environment.getProperty("spring.profiles.default")).isEqualTo("local");
        assertThat(environment.getProperty("app.auth.token-secret")).isNotBlank();
    }
}
