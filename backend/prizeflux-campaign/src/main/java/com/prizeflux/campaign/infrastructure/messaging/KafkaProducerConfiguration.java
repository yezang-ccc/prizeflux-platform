package com.prizeflux.campaign.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 显式构造 KafkaTemplate&lt;String,String&gt;，避免依赖 Boot 自动装配的通配泛型
 * （KafkaTemplate&lt;?,?&gt;）带来的类型注入歧义，也让序列化器与可靠性参数一目了然。
 * 与 {@link KafkaEventPublisher} 一样按 prizeflux.kafka.enabled 门控；不启用时此配置不加载。
 */
@Configuration
@ConditionalOnProperty(name = "prizeflux.kafka.enabled", havingValue = "true")
public class KafkaProducerConfiguration {

    @Bean
    KafkaTemplate<String, String> fulfillmentKafkaTemplate(
            @Value("${prizeflux.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // acks=all 与幂等开启搭配：让"投递成功"可依赖（至少所有分区副本确认）。
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // send() 可能在取元数据阶段阻塞，不能只依赖 KafkaEventPublisher 的 Future 超时。
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }
}
