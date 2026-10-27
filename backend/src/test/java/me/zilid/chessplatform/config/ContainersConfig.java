package me.zilid.chessplatform.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {
    public static GenericContainer<?> rabbitMqContainer() {
        return new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management"))
                .withEnv("RABBITMQ_DEFAULT_USER", "chess")
                .withEnv("RABBITMQ_DEFAULT_PASS", "password")
                .withCopyToContainer(
                        Transferable.of("[rabbitmq_management,rabbitmq_stomp]."), "/etc/rabbitmq/enabled_plugins")
                .withExposedPorts(61613, 5672);
    }

    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.6")).withExposedPorts(6379);
    }

    @Bean
    public GenericContainer<?> rabbitMQContainer() {
        return rabbitMqContainer();
    }

    @Bean
    public DynamicPropertyRegistrar relayPropertyRegistrar(GenericContainer<?> rabbitMQContainer) {
        return registry -> {
            registry.add("app.websocket.relay.enabled", () -> "true");
            registry.add("app.websocket.relay.host", rabbitMQContainer::getHost);
            registry.add("app.websocket.relay.port", () -> rabbitMQContainer.getMappedPort(61613));
            registry.add("app.websocket.relay.login", () -> "chess");
            registry.add("app.websocket.relay.passcode", () -> "password");
            registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
            registry.add("spring.rabbitmq.port", () -> rabbitMQContainer.getMappedPort(5672));
            registry.add("spring.rabbitmq.username", () -> "chess");
            registry.add("spring.rabbitmq.password", () -> "password");
        };
    }
}
