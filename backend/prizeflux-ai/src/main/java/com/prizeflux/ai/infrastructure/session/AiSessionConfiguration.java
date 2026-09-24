package com.prizeflux.ai.infrastructure.session;

import com.prizeflux.ai.harness.context.ContextWindowBuilder;
import com.prizeflux.ai.harness.session.SessionPolicy;
import com.prizeflux.ai.harness.session.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiSessionConfiguration {
    @Bean
    SessionStore sessionStore() {
        return new InMemorySessionStore(SessionPolicy.defaults());
    }

    @Bean
    ContextWindowBuilder contextWindowBuilder() {
        return new ContextWindowBuilder(24, 12_000);
    }
}
