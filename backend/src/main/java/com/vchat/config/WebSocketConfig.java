package com.vchat.config;

import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket. Clients CONNECT with an "Authorization: Bearer <access token>" STOMP header and subscribe to
 * /user/queue/events; every server push for that user goes there. Clients send typing indicators to /app/typing.
 * Tokens are checked at CONNECT; a revoked session is told to sign out with a "session.revoked" event.
 *
 * Clients may only SEND to /app/** (our @MessageMapping handlers) and SUBSCRIBE to their own /user/queue/**.
 * Without this, any signed-in user could SEND straight to the broker, e.g. to /user/{someoneElse}/queue/events,
 * and push forged events (fake messages, "session.revoked") into another user's socket.
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
                if (acc == null || acc.getCommand() == null) return message;
                switch (acc.getCommand()) {
                    case CONNECT -> {
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
                    case SEND -> requireDestination(acc, "/app/");
                    case SUBSCRIBE -> requireDestination(acc, "/user/queue/");
                    default -> { }
                }
                return message;
            }
        });
    }

    /** Signed-in session and an allowed destination, or the frame is rejected (the client gets a STOMP ERROR). */
    static void requireDestination(StompHeaderAccessor acc, String allowedPrefix) {
        if (!(acc.getUser() instanceof AuthUser)) throw new AccessDeniedException("Not signed in");
        String destination = acc.getDestination();
        if (destination == null || !destination.startsWith(allowedPrefix) || destination.contains("..")) {
            throw new AccessDeniedException("Destination not allowed: " + destination);
        }
    }
}
