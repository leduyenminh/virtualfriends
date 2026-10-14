package com.vchat.service;

import com.vchat.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

public final class Current {
    private Current() {}

    public static UUID userId() { return user().id(); }

    public static AuthUser user() {
        AuthUser u = userOrNull();
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return u;
    }

    public static AuthUser userOrNull() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser u ? u : null;
    }
}
