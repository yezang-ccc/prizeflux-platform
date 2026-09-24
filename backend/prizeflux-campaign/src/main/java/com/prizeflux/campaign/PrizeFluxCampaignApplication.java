package com.prizeflux.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Campaign 服务的 Java 启动入口，属于“应用装配层”。
 *
 * <p>谁会调用它：开发者执行 {@code spring-boot:run}，或服务器执行打包后的 JAR 时，
 * JVM 会从这个类的 {@link #main(String[])} 方法开始执行。</p>
 *
 * <p>{@code @SpringBootApplication} 是一个组合注解。它告诉 Spring Boot：</p>
 * <ul>
 *     <li>这是一个 Spring Boot 应用；</li>
 *     <li>从当前包 {@code com.prizeflux.campaign} 向下扫描组件；</li>
 *     <li>根据 POM 中的依赖自动配置 Web 服务器和 Spring MVC。</li>
 * </ul>
 *
 * <p>因此，放在子包 {@code com.prizeflux.campaign.controller} 中的
 * {@code HealthController} 才会被扫描、创建为 Spring Bean，并能接收 HTTP 请求。</p>
 */
@SpringBootApplication
public class PrizeFluxCampaignApplication {

    /**
     * 应用的标准 Java 入口。
     *
     * JVM 会把命令行参数放进 args；本任务暂不读取它们，但 Java 启动入口必须保留这个标准签名。
     */
    public static void main(String[] args) {
        // 创建 Spring IoC 容器、扫描组件、完成自动配置，并启动内嵌 Tomcat Web 服务器。
        SpringApplication.run(PrizeFluxCampaignApplication.class, args);
    }
}
