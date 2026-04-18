package com.example.dashboard.api.websocket.config;

import com.example.dashboard.api.websocket.UserHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // To carry the messages back to the client
        registry.enableSimpleBroker("/topic");
        // To filter destinations targeting application annotated methods (via @MessageMapping).
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(userHandshakeInterceptor);
    }
}
