package com.example.dashboard.api.websocket.config;

import com.example.dashboard.api.websocket.UserHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket configuration.
 *
 * The in-memory simple broker relays messages within this JVM. Cross-node
 * fan-out (Redis Pub/Sub) is deferred.
 *
 * Identity is established on the STOMP CONNECT frame via {@link UserHandshakeInterceptor},
 * which validates X-Dashboard-Id / X-Username headers and claims a per-dashboard
 * unique username in the in-memory presence registry.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.websocket.allowed-origins:*}")
    private String allowedOrigins;

    private final UserHandshakeInterceptor userHandshakeInterceptor;

    /**
     * Scheduler used by the simple broker to emit STOMP heartbeat frames.
     * Required as soon as {@code setHeartbeatValue(...)} is configured — without
     * it, {@code SimpleBrokerMessageHandler} fails to start with
     * "Heartbeat value configured but no TaskScheduler provided".
     */
    @Bean
    public TaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Activates an in-memory broker to handle message subscriptions and carry the messages back to the client
        registry.enableSimpleBroker("/topic")
                // Because WebSocket connections remain open, they must be monitored to prevent stale sessions from
                // consuming server resources. Spring Boot relies on HeartbeatInterceptor to send periodic ping messages
                // between the client and server. This keeps the connection active and detects disconnected clients.
                // This sends a heartbeat from the server every 10 seconds and expects a response from the client every
                // 60 seconds. If a client fails to respond within the expected time frame, Spring Boot closes the
                // connection to free up resources.
                .setHeartbeatValue(new long[]{10_000, 60_000})
                // Required whenever a heartbeat is configured — otherwise the broker
                // cannot schedule the ping frames and the bean fails to start.
                .setTaskScheduler(stompHeartbeatScheduler());
        // Defines a namespace for messages sent by clients (handled via @MessageMapping), keeping them separate from broker destinations
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Defines where clients establish WebSocket connections
        registry.addEndpoint("/ws")
                // Permits cross-origin requests, though in production, it should be restricted to trusted domains
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                // Adds fallback support for browsers that don’t support native WebSockets by emulating the behavior over HTTP
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(userHandshakeInterceptor);
    }
}
