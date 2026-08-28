package com.aioj.next.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class JwtTokenService {
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final Key signingKey;
    private final Key verificationKey;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        if (hasText(properties.getPrivateKeyPem()) && hasText(properties.getPublicKeyPem())) {
            this.signingKey = readPrivateKey(properties.getPrivateKeyPem());
            this.verificationKey = readPublicKey(properties.getPublicKeyPem());
        } else {
            SecretKey secretKey = Keys.hmacShaKeyFor(stableSecret(properties.getHmacSecret()));
            this.signingKey = secretKey;
            this.verificationKey = secretKey;
        }
    }

    public String createAccessToken(Long userId, String account, Collection<Role> roles) {
        return createAccessToken(userId, account, roles, false);
    }

    public String createAccessToken(Long userId, String account, Collection<Role> roles, boolean passwordResetRequired) {
        return createToken(userId, account, roles, TOKEN_TYPE_ACCESS, properties.getAccessTtl().toSeconds(), passwordResetRequired);
    }

    public String createRefreshToken(Long userId, String account, Collection<Role> roles) {
        return createRefreshToken(userId, account, roles, false);
    }

    public String createRefreshToken(Long userId, String account, Collection<Role> roles, boolean passwordResetRequired) {
        return createToken(userId, account, roles, TOKEN_TYPE_REFRESH, properties.getRefreshTtl().toSeconds(), passwordResetRequired);
    }

    public Claims parse(String token) {
        var parser = Jwts.parser().requireIssuer(properties.getIssuer());
        if (verificationKey instanceof SecretKey secretKey) {
            parser.verifyWith(secretKey);
        } else {
            parser.verifyWith((PublicKey) verificationKey);
        }
        return parser.build().parseSignedClaims(token).getPayload();
    }

    public SecurityPrincipal toPrincipal(Claims claims) {
        List<String> roleNames = claims.get("roles", List.class);
        var roles = roleNames.stream().map(Role::valueOf).collect(java.util.stream.Collectors.toSet());
        Long userId = Long.valueOf(claims.getSubject());
        String account = claims.get("account", String.class);
        Boolean passwordResetRequired = claims.get("pwd_reset", Boolean.class);
        return new SecurityPrincipal(userId, account, roles, Boolean.TRUE.equals(passwordResetRequired));
    }

    private String createToken(Long userId, String account, Collection<Role> roles, String tokenType, long ttlSeconds,
                               boolean passwordResetRequired) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim("account", account)
                .claim("roles", roles.stream().map(Role::name).toList())
                .claim("pwd_reset", passwordResetRequired)
                .claim("typ", tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (signingKey instanceof SecretKey secretKey) {
            return builder.signWith(secretKey).compact();
        }
        return builder.signWith((PrivateKey) signingKey).compact();
    }

    private byte[] stableSecret(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((hasText(raw) ? raw : "ai-oj-next-dev-secret").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize JWT secret", ex);
        }
    }

    private PrivateKey readPrivateKey(String source) {
        try {
            String pem = resolveText(source)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read JWT private key", ex);
        }
    }

    private PublicKey readPublicKey(String source) {
        try {
            String pem = resolveText(source)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read JWT public key", ex);
        }
    }

    private String resolveText(String source) throws Exception {
        if (source.startsWith("file:")) {
            return Files.readString(Path.of(source.substring("file:".length())));
        }
        Path maybePath = Path.of(source);
        if (Files.exists(maybePath)) {
            return Files.readString(maybePath);
        }
        return source;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
