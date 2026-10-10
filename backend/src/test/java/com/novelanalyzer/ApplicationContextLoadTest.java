package com.novelanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动面回归门禁：验证完整 Spring 上下文可以刷新。
 *
 * <p>单元/切片测试直接 new 出被测类，因此不会覆盖 Bean 装配错误
 * （例如多构造器缺少 {@code @Autowired}）。这类缺陷只会在容器启动时
 * 暴露，表现为端口不监听、readiness 探测持续 connection refused。
 * 该测试用 H2 内存库替换 MySQL，不依赖 Redis/RabbitMQ 等外部服务，
 * 因此可以在候选构建阶段作为发布前置门禁运行。
 */
@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:contextloaddb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100"
    }
)
class ApplicationContextLoadTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldRefreshApplicationContextWithAllSingletonBeans() {
        assertThat(applicationContext).isNotNull();
        // getBeanDefinitionCount 仅证明定义存在；上下文刷新成功本身已强制
        // 实例化全部非懒加载单例，任何构造器解析失败都会让该测试失败。
        assertThat(applicationContext.getBeanDefinitionCount()).isPositive();
    }
}
