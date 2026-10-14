package com.vchat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Access log + correlation id. Every request gets a requestId (taken from X-Request-Id when a proxy already set
 * one) that is put in the MDC, so every log line written while handling the request carries it, and it is
 * echoed back in the response header for support/debugging.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("vchat.access");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader("X-Request-Id");
        String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().substring(0, 13);
        MDC.put("requestId", id);
        res.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String uri = req.getRequestURI();
            if (!uri.startsWith("/actuator/health") && !uri.startsWith("/files/")) {
                if (res.getStatus() >= 500) log.error("{} {} -> {} in {} ms", req.getMethod(), uri, res.getStatus(), ms);
                else log.info("{} {} -> {} in {} ms", req.getMethod(), uri, res.getStatus(), ms);
            }
            MDC.clear();
        }
    }
}
