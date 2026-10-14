package com.vchat.config;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Turns "Authorization: Bearer <access token>" into an authenticated principal for this request:
 * signature + expiry are checked by JwtService, early revocation (logout, disabled account) by Redis.
 * Anything wrong -> the request stays anonymous and protected endpoints answer 401.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final TokenRevocationService revocation;

    public JwtFilter(JwtService jwt, TokenRevocationService revocation) { this.jwt = jwt; this.revocation = revocation; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthUser user = jwt.parse(header.substring(7));
                if (!revocation.isRevoked(user)) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
                    MDC.put("userId", user.id().toString()); // shows up in JSON logs; cleared by RequestLoggingFilter
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // fall through unauthenticated; the security chain returns 401
            }
        }
        chain.doFilter(req, res);
    }
}
