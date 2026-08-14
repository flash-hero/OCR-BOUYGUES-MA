package com.bycn.edoc.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Contrôle d'accès à {@code /api/**} par clé partagée ({@code X-Api-Key}).
 *
 * <p>Le moteur n'est pas destiné à être joignable depuis un navigateur : son seul client est le
 * back-end eDoc, qui appelle de serveur à serveur. Une clé partagée suffit donc, et évite d'avoir
 * à embarquer un jeton dans du code exécuté côté navigateur.</p>
 *
 * <p>La comparaison est à <b>temps constant</b> : une comparaison naïve s'arrête au premier
 * caractère différent, ce qui laisse mesurer la clé caractère par caractère.</p>
 *
 * <p>Si aucune clé n'est configurée, le filtre laisse tout passer (développement local) — c'est
 * signalé au démarrage par {@link ApiConfig}.</p>
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private final ApiProperties properties;

    public ApiKeyFilter(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.hasKey()) {
            chain.doFilter(request, response);
            return;
        }
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Clé d'API absente ou invalide (en-tête " + HEADER + ").\"}");
    }

    private boolean matches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                properties.key().getBytes(StandardCharsets.UTF_8));
    }
}
