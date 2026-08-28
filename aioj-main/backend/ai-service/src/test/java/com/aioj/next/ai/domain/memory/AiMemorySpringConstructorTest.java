package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AiMemorySpringConstructorTest {
    @Test
    void servicesWithTestingConstructorsDeclareAutowiredProductionConstructors() throws NoSuchMethodException {
        assertThat(AiMemoryEventService.class.getConstructor(
                AiDomainEventMapper.class,
                AiMemoryJobService.class,
                AiMemoryEventPayloadSanitizer.class,
                ObjectMapper.class
        ).isAnnotationPresent(Autowired.class)).isTrue();

        assertThat(AiMemoryJobService.class.getConstructor(
                AiMemoryJobMapper.class,
                AiMemoryEventPayloadSanitizer.class,
                ObjectMapper.class,
                AiProperties.class
        ).isAnnotationPresent(Autowired.class)).isTrue();
    }
}
