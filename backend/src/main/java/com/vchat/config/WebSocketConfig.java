package com.vchat.config;

import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
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
    /**
     * STOMP heart-beats as [server sends every, server expects every] ms. Proxies such as Cloudflare close a WebSocket
     * after ~100 s without traffic, and the browser client (realtime.js asks for 20 s) gives up after 40 s of silence.
     * The broker checks every 10 s, so its pings arrive at most ~30 s apart. It doesn't require pings back: browsers
     * throttle timers in background tabs, and a late client ping must not get a healthy socket closed.
     */
    static final long[] HEARTBEAT = {10_000, 0};

    private final JwtService jwt;
    private final TokenRevocationService revocation;
    private final VchatProperties props;
    private final TaskScheduler heartbeatScheduler;

    /** The scheduler is the one Spring's WebSocket config declares; @Lazy breaks the cycle with that config. */
    public WebSocketConfig(JwtService jwt, TokenRevocationService revocation, VchatProperties props,
                           @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
        this.jwt = jwt; this.revocation = revocation; this.props = props; this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(props.corsOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic").setHeartbeatValue(HEARTBEAT).setTaskScheduler(heartbeatScheduler);
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
