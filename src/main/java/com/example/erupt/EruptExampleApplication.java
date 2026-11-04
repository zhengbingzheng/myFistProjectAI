package com.example.erupt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p>分层约定：</p>
 * <pre>
 * controller  —— 视图层：参数绑定、校验、响应封装，不含业务判断
 * service     —— 服务层：用例编排、事务边界、领域规则
 * repository  —— 数据层：实体 + Mapper（MyBatis），只负责持久化，不感知 Web
 * </pre>
 */
@SpringBootApplication
public class EruptExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(EruptExampleApplication.class, args);
    }
}
