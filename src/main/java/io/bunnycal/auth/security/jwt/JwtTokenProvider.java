package io.bunnycal.auth.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expirationMillis
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters long");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMillis;
    }

    // ✅ UPDATED: No User entity here
    public String generateAccessToken(UUID userId, String email) {
        return generateAccessToken(userId, email, List.of());
    }

    /**
     * Mints an access token. When {@code roles} is non-empty the admin role names are
     * embedded as a {@code roles} claim; {@code JwtAuthenticationFilter} maps them to
     * {@code ROLE_*} authorities. Normal users pass an empty list, so the claim is absent
     * and existing tokens/behavior are unchanged.
     */
    public String generateAccessToken(UUID userId, String email, Collection<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMillis);

        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("type", "access");

        if (roles != null && !roles.isEmpty()) {
            builder.claim("roles", List.copyOf(roles));
        }

        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** Admin role names embedded in the token, or an empty list for a normal user. */
    @SuppressWarnings("unchecked")
    public List<String> getRolesFromClaims(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public Claims getClaims(String token) {
        return parseClaims(token);
    }

    public UUID getUserIdFromClaims(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.trim().isEmpty()) {
            throw new IllegalArgumentException("Token subject is missing");
        }
        return UUID.fromString(subject);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}