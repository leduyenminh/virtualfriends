package com.vchat.config;

import com.vchat.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * End of the Google/Facebook round-trip: find or create the local account, start a session by setting the
 * httpOnly refresh cookie, and send the browser back to the SPA. No token travels in the URL: the SPA calls
 * /api/auth/refresh on load and gets its access token from there.
 */
@Component
public class OAuthLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuthLoginHandler.class);

    private final AuthService auth;
    private final AuthCookies cookies;
    private final String frontendUrl;

    public OAuthLoginHandler(AuthService auth, AuthCookies cookies, VchatProperties props) {
        this.auth = auth;
        this.cookies = cookies;
        this.frontendUrl = props.frontendUrl().replaceAll("/$", "");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2User user = token.getPrincipal();
        String subject = user.getName(); // Google: "sub", Facebook: "id"
        String picture = "google".equals(provider)
                ? user.getAttribute("picture")
                : "https://graph.facebook.com/" + subject + "/picture?type=large";
        endSession(req);
        try {
            AuthService.Session s = auth.loginWithIdentity(provider, subject, user.getAttribute("email"), user.getAttribute("name"), picture);
            cookies.set(req, res, s.refresh().raw(), s.refresh().expiresAt());
            res.sendRedirect(frontendUrl + "/#/oauth");
        } catch (ResponseStatusException e) {
            redirectError(res, e.getReason());
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        log.warn("OAuth login failed: {}", e.getMessage());
        endSession(req);
        redirectError(res, "Sign-in was cancelled or failed");
    }

    private void redirectError(HttpServletResponse res, String message) throws IOException {
        res.sendRedirect(frontendUrl + "/#/oauth?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static void endSession(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
    }
}
