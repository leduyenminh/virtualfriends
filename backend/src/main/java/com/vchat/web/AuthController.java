package com.vchat.web;

import com.vchat.config.AuthCookies;
import com.vchat.config.VchatProperties;
import com.vchat.dto.Dtos.*;
import com.vchat.service.AuthService;
import com.vchat.service.Current;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/**
 * Sign-in endpoints. Successful sign-in returns the access token in the JSON body (the SPA keeps it in memory) and
 * sets the refresh token as an httpOnly cookie scoped to /api/auth.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthCookies cookies;
    private final VchatProperties props;

    public AuthController(AuthService auth, AuthCookies cookies, VchatProperties props) {
        this.auth = auth; this.cookies = cookies; this.props = props;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest r, HttpServletRequest req, HttpServletResponse res) {
        return start(auth.register(r), req, res);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        return start(auth.login(r), req, res);
    }

    /** Called on page load and whenever the access token expires. */
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest req, HttpServletResponse res) {
        requireSameOrigin(req);
        try {
            AuthService.Session s = auth.refresh(cookies.read(req));
            if (s.refresh() != null) cookies.set(req, res, s.refresh().raw(), s.refresh().expiresAt());
            return s.body();
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 401) cookies.clear(req, res);
            throw e;
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest req, HttpServletResponse res) {
        requireSameOrigin(req);
        auth.logout(cookies.read(req), Current.userOrNull());
        cookies.clear(req, res);
    }

    @PostMapping("/logout-all")
    public void logoutAll(HttpServletRequest req, HttpServletResponse res) {
        requireSameOrigin(req);
        auth.logoutEverywhere(Current.userId(), "You signed out on all devices");
        cookies.clear(req, res);
    }

    /** Which social login buttons the sign-in screen should show. Start the flow at /oauth2/authorization/{id}. */
    @GetMapping("/providers")
    public List<String> providers() { return props.oauth() == null ? List.of() : props.oauth().enabledProviders(); }

    private AuthResponse start(AuthService.Session s, HttpServletRequest req, HttpServletResponse res) {
        cookies.set(req, res, s.refresh().raw(), s.refresh().expiresAt());
        return s.body();
    }

    /** Cookie-authenticated endpoints only accept requests from our own pages (defence in depth next to SameSite). */
    private void requireSameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && !props.corsOrigins().contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site request blocked");
        }
    }
}
