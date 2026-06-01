package io.bunnycal.auth.security.filter;

import io.bunnycal.auth.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_COOKIE = "accessToken";

    private final JwtTokenProvider jwtTokenProvider;
    private final boolean cookieFallbackEnabled;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            @Value("${auth.access-token.cookie-fallback-enabled:true}") boolean cookieFallbackEnabled
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.cookieFallbackEnabled = cookieFallbackEnabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            if (log.isDebugEnabled()) {
                log.debug(
                        "jwt_filter_enter method={} uri={} cookieFallbackEnabled={} cookies={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        cookieFallbackEnabled,
                        describeCookies(request)
                );
            }

            // Avoid re-authentication
            Authentication existing = SecurityContextHolder.getContext().getAuthentication();
            if (existing == null) {

                String token = resolveToken(request);

                if (token != null) {
                    authenticate(token, request);
                } else if (log.isDebugEnabled()) {
                    log.debug("jwt_filter_no_token method={} uri={}", request.getMethod(), request.getRequestURI());
                }
            } else if (log.isDebugEnabled()) {
                log.debug(
                        "jwt_filter_skip_existing_auth method={} uri={} principalType={} authenticated={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        existing.getPrincipal() == null ? "null" : existing.getPrincipal().getClass().getName(),
                        existing.isAuthenticated()
                );
            }
        } catch (Exception ex) {
            // 🔥 Important: never break the filter chain
            log.warn("jwt_filter_exception method={} uri={} message={}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        }

        filterChain.doFilter(request, response);

        if (log.isDebugEnabled()) {
            Authentication authAfter = SecurityContextHolder.getContext().getAuthentication();
            log.debug(
                    "jwt_filter_exit method={} uri={} authClass={} principal={} authenticated={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    authAfter == null ? "null" : authAfter.getClass().getName(),
                    authAfter == null || authAfter.getPrincipal() == null ? "null" : authAfter.getPrincipal().toString(),
                    authAfter != null && authAfter.isAuthenticated()
            );
        }
    }

    private void authenticate(String token, HttpServletRequest request) {
        boolean valid = jwtTokenProvider.validateToken(token);
        if (log.isDebugEnabled()) {
            log.debug(
                    "jwt_filter_validation uri={} valid={} tokenLength={}",
                    request.getRequestURI(),
                    valid,
                    token.length()
            );
        }
        if (!valid) {
            // Optionally log or trigger metrics
            log.debug("Invalid or expired JWT");
            return;
        }

        Claims claims = jwtTokenProvider.getClaims(token);
        UUID userId = jwtTokenProvider.getUserIdFromClaims(claims);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        Collections.emptyList() // replace later with roles if needed
                );

        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        if (log.isDebugEnabled()) {
            log.debug(
                    "jwt_filter_context_set uri={} principal={} authorities={}",
                    request.getRequestURI(),
                    userId,
                    authentication.getAuthorities().size()
            );
        }
    }

    private String resolveToken(HttpServletRequest request) {

        // 🔹 1. Try Authorization header first
        String authorization = request.getHeader(AUTHORIZATION_HEADER);

        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("jwt_filter_token_source uri={} source=authorization_header", request.getRequestURI());
                }
                return token;
            }
        }

        // 🔹 2. Optional fallback to HttpOnly cookie (temporary transition mode)
        if (cookieFallbackEnabled) {
            String cookieToken = getCookieValue(request, ACCESS_TOKEN_COOKIE);
            if (log.isDebugEnabled()) {
                log.debug(
                        "jwt_filter_token_source uri={} source={} cookieName={}",
                        request.getRequestURI(),
                        cookieToken == null ? "none" : "cookie",
                        ACCESS_TOKEN_COOKIE
                );
            }
            return cookieToken;
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "jwt_filter_cookie_fallback_disabled uri={} expectedCookieName={}",
                    request.getRequestURI(),
                    ACCESS_TOKEN_COOKIE
            );
        }
        return null;
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;

        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String describeCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return "[]";
        }
        List<String> compact = new java.util.ArrayList<>(cookies.length);
        for (Cookie cookie : cookies) {
            String value = cookie.getValue();
            int len = value == null ? 0 : value.length();
            compact.add(cookie.getName() + "(len=" + len + ")");
        }
        return compact.toString();
    }
}
