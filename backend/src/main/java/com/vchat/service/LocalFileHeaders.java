package com.vchat.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * In LOCAL storage mode uploads share the API origin, so lock them down. Everything is sandboxed (opaque origin, so
 * an uploaded SVG/HTML can never read the app's storage). Model folders may run scripts so Unity WebGL builds work
 * inside their iframe; stickers and attachments may not.
 */
@Component
public class LocalFileHeaders extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/files/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean model = req.getRequestURI().startsWith("/files/model/");
        res.setHeader("Content-Security-Policy", model
                ? "sandbox allow-scripts; default-src * data: blob: 'unsafe-inline' 'unsafe-eval'"
                : "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Access-Control-Allow-Origin", "*");
        chain.doFilter(req, res);
    }
}
