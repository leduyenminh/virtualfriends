package com.vchat.config;

import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket. Clients CONNECT with an "Authorization: Bearer <access token>" STOMP header and subscribe to
 * /user/queue/events; every server push for that user goes there. Clients send typing indicators to /app/typing.
 * Tokens are checked at CONNECT; a revoked session is told to sign out with a "session.revoked" event.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwt;
    private final TokenRevocationService revocation;
    private final VchatProperties props;

    public WebSocketConfig(JwtService jwt, TokenRevocationService revocation, VchatProperties props) {
        this.jwt = jwt; this.revocation = revocation; this.props = props;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(props.corsOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc != null && StompCommand.CONNECT.equals(acc.getCommand())) {
                    String header = acc.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) {
                        throw new IllegalArgumentException("Missing bearer token");
                    }
                    try {
                        AuthUser user = jwt.parse(header.substring(7));
                        if (revocation.isRevoked(user)) throw new IllegalArgumentException("Token revoked");
                        acc.setUser(user);
                    } catch (JwtException e) {
                        throw new IllegalArgumentException("Invalid token");
                    }
                }
                return message;
            }
        });
    }
}
