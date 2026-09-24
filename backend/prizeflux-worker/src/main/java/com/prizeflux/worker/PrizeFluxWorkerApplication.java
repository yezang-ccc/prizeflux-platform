package com.prizeflux.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Worker 独立进程入口：只消费发奖事件，不提供 HTTP 服务。
 * {@code @EnableKafka} 显式启用 @KafkaListener 处理，与 Boot 自动装配的消费者工厂配合使用。
 */
@SpringBootApplication
@EnableKafka
public class PrizeFluxWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrizeFluxWorkerApplication.class, args);
    }
}
