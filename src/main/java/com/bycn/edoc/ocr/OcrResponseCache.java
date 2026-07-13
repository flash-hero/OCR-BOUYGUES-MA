package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache de réponses OCR <b>adressé par contenu</b> : évite de re-facturer un appel Mistral déjà
 * effectué à l'identique.
 *
 * <p>La clé est le SHA-256 du corps de requête sérialisé. Ce corps contient déjà <em>tout</em> ce
 * qui détermine la réponse : le modèle, le prompt, le schéma d'annotation et les octets base64 du
 * document ou de l'image. Deux requêtes identiques produisent donc la même clé — et comme le rendu
 * PDF→PNG est déterministe, re-lancer le test sur un document déjà traité (même coin, même
 * résolution) tombe sur un fichier cache et ne coûte rien. Changer un prompt ou la résolution de
 * découpage modifie le corps, donc la clé : le cache s'invalide tout seul pour les appels concernés.</p>
 *
 * <p>Le cache est <b>best-effort</b> : toute erreur d'E/S (fichier illisible, écriture impossible)
 * est traitée comme un défaut de cache et ne fait jamais échouer l'OCR.</p>
 */
public final class OcrResponseCache {

    private static final String CACHE_SUFFIX = ".json";

    /** Répertoire du cache, ou {@code null} si le cache est désactivé. */
    private final Path dir;
    private final ObjectMapper mapper;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    /** Un cache désactivé : {@link #get} rate toujours, {@link #put} ne fait rien. */
    public static OcrResponseCache disabled() {
        return new OcrResponseCache(null, new ObjectMapper());
    }

    public OcrResponseCache(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return dir != null;
    }

    /** Répertoire du cache (pour affichage), ou {@code null} si désactivé. */
    public Path directory() {
        return dir;
    }

    /** Réponse déjà en cache pour ce corps de requête, ou vide s'il faut appeler l'API. */
    public Optional<JsonNode> get(byte[] requestBytes) {
        if (dir == null) {
            return Optional.empty();
        }
        Path file = dir.resolve(key(requestBytes) + CACHE_SUFFIX);
        if (!Files.isRegularFile(file)) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(file));
            hits.incrementAndGet();
            return Optional.of(node);
        } catch (IOException e) {
            // Fichier illisible : on considère que c'est un défaut de cache (best-effort).
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    /** Mémorise la réponse pour ce corps de requête (écriture atomique via fichier temporaire). */
    public void put(byte[] requestBytes, JsonNode response) {
        if (dir == null || response == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(key(requestBytes) + CACHE_SUFFIX);
            Path tmp = Files.createTempFile(dir, "ocr", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(response));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Best-effort : on ne fait jamais échouer l'OCR à cause du cache.
            System.err.println("[cache] écriture impossible : " + e.getMessage());
        }
    }

    /** Nombre de réponses servies depuis le cache (aucun coût API). */
    public int hits() {
        return hits.get();
    }

    /** Nombre d'appels ayant nécessité (ou nécessitant) un vrai appel réseau. */
    public int misses() {
        return misses.get();
    }

    private static String key(byte[] requestBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(requestBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible sur cette JVM", e);
        }
    }
}
