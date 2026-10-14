package com.vchat.aop;

import com.vchat.config.AuthUser;
import com.vchat.config.VchatProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;

/**
 * Protects the expensive or abusable entry points (login brute force, message spam, paid TTS/LLM calls).
 * Fails open: if Redis is unreachable the request is allowed and a warning is logged.
 */
@Aspect
@Component
@Order(1)
public class RateLimitAspect {
    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

    public RateLimitAspect(StringRedisTemplate redis, VchatProperties props) {
        this.redis = redis;
        this.enabled = props.rateLimit() == null || props.rateLimit().enabled();
    }

    @Around("@annotation(limit)")
    public Object check(ProceedingJoinPoint pjp, RateLimited limit) throws Throwable {
        if (enabled) {
            String subject = subject(pjp, limit);
            long window = Instant.now().getEpochSecond() / limit.windowSeconds();
            String key = "rl:" + limit.name() + ":" + subject + ":" + window;
            Long count = null;
            try {
                count = redis.opsForValue().increment(key);
                if (count != null && count == 1) redis.expire(key, Duration.ofSeconds(limit.windowSeconds()));
            } catch (DataAccessException e) {
                log.warn("Rate limiter unavailable ({}), allowing request", e.getMessage());
            }
            if (count != null && count > limit.limit()) {
                log.info("Rate limit '{}' hit by {}", limit.name(), subject);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, limit.message());
            }
        }
        return pjp.proceed();
    }

    private String subject(ProceedingJoinPoint pjp, RateLimited limit) {
        if ("ip".equals(limit.key())) return clientIp();
        if (limit.key().isEmpty()) {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            return a != null && a.getPrincipal() instanceof AuthUser u ? "u:" + u.id() : "ip:" + clientIp();
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        var ctx = new MethodBasedEvaluationContext(pjp.getTarget(), sig.getMethod(), pjp.getArgs(), names);
        Object v = parser.parseExpression(limit.key()).getValue(ctx);
        return String.valueOf(v).toLowerCase();
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr(); // already the real client IP: forward-headers-strategy=framework
        }
        return "unknown";
    }
}
