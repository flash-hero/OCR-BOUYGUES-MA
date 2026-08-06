package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Câblage Spring du client OCR.
 *
 * <p>On force un {@link SimpleClientHttpRequestFactory} qui <b>bufferise</b> le corps de requête
 * et pose donc un en-tête {@code Content-Length}. La passerelle Azure AI Foundry (APIM) rejette
 * en effet le {@code Transfer-Encoding: chunked} émis par défaut par le client HTTP du JDK
 * (« Content-Length header is required to make request »).</p>
 */
@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);

    @Bean
    public OcrResponseCache ocrResponseCache(OcrProperties props, ObjectMapper mapper) {
        // Cache adressé par contenu : re-lancer le test sur un document déjà traité ne coûte rien.
        if (!props.cacheEnabled() || props.cacheDir() == null || props.cacheDir().isBlank()) {
            return OcrResponseCache.disabled();
        }
        return new OcrResponseCache(Path.of(props.cacheDir()), mapper);
    }

    @Bean
    public OcrClient ocrClient(OcrProperties props,
                                             RestClient.Builder builder,
                                             ObjectMapper mapper,
                                             OcrResponseCache cache) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // bufferRequestBody = true par defaut => Content-Length pose, pas de chunked.
        return new OcrClient(props, builder.requestFactory(factory), mapper, cache);
    }

    @Bean
    public TwoPassCartoucheExtractor twoPassCartoucheExtractor(
            OcrClient client,
            @Value("${edoc.force-corner:}") String forcedCorner,
            @Value("${ocr.crop-long-px:3400}") int cropLongPx,
            @Value("${ocr.locate-long-px:1400}") int locateLongPx,
            @Value("${ocr.corner-fraction:0.40}") double cornerFraction,
            @Value("${ocr.locate-enabled:true}") boolean locateEnabled,
            @Value("${ocr.ink-bounds-enabled:true}") boolean inkBoundsEnabled,
            @Value("${ocr.text-layer-enabled:true}") boolean textLayerEnabled) {
        // edoc.force-corner : diagnostic uniquement (vide par defaut). Force la zone de decoupage
        // sur les grands plans et court-circuite la passe 1, pour isoler mecanisme vs localisation.
        // Les autres proprietes sont les leviers de geometrie/latence des grands plans (voir yml).
        return new TwoPassCartoucheExtractor(client, forcedCorner, cropLongPx, locateLongPx,
                cornerFraction, locateEnabled, inkBoundsEnabled, textLayerEnabled);
    }
}
