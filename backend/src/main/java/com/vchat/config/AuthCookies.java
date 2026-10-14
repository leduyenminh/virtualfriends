package com.vchat.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;

/**
 * The refresh token cookie:
 *   HttpOnly        page scripts (and therefore XSS) can't read it
 *   SameSite=Strict never sent on requests initiated by other sites (CSRF)
 *   Path=/api/auth  only sent to refresh/logout, not with every API call
 *   Secure          HTTPS only (see vchat.auth.cookie-secure)
 */
@Component
public class AuthCookies {
    public static final String NAME = "vchat_rt";
    private static final String PATH = "/api/auth";

    private final String secureMode;

    public AuthCookies(VchatProperties props) { this.secureMode = props.auth().cookieSecure(); }

    public void set(HttpServletRequest req, HttpServletResponse res, String value, Instant expiresAt) {
        res.addHeader(HttpHeaders.SET_COOKIE, base(req, value)
                .maxAge(Duration.between(Instant.now(), expiresAt)).build().toString());
    }

    public void clear(HttpServletRequest req, HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, base(req, "").maxAge(0).build().toString());
    }

    public String read(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (NAME.equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(HttpServletRequest req, String value) {
        boolean secure = "true".equalsIgnoreCase(secureMode) || ("auto".equalsIgnoreCase(secureMode) && req.isSecure());
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
    }
}
