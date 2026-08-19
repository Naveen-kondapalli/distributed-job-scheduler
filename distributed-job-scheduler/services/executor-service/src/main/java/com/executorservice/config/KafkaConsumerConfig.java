package com.executorservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            ExecutorProperties properties,
            CommonErrorHandler commonErrorHandler,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getKafka().getConcurrency());
        factory.setAutoStartup(autoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(commonErrorHandler);
        return factory;
    }

    @Bean
    CommonErrorHandler commonErrorHandler() {
        return new CommonErrorHandler() {
            @Override
            public boolean handleOne(Exception thrownException, org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                                     org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, MessageListenerContainer container) {
                log.error(
                        "Kafka listener error left uncommitted: topic={}, partition={}, offset={}, key={}, reason={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        thrownException.getMessage()
                );
                return true;
            }

            @Override
            public void handleOtherException(
                    Exception thrownException,
                    org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                    MessageListenerContainer container,
                    boolean batchListener
            ) {
                log.error("Kafka listener infrastructure error", thrownException);
            }
        };
    }
}
