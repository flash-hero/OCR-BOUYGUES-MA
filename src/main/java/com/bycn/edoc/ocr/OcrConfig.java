package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@EnableConfigurationProperties(MistralOcrProperties.class)
public class OcrConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);

    @Bean
    public MistralOcrClient mistralOcrClient(MistralOcrProperties props,
                                             RestClient.Builder builder,
                                             ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // bufferRequestBody = true par defaut => Content-Length pose, pas de chunked.
        return new MistralOcrClient(props, builder.requestFactory(factory), mapper);
    }

    @Bean
    public TwoPassCartoucheExtractor twoPassCartoucheExtractor(
            MistralOcrClient client,
            @Value("${edoc.force-corner:}") String forcedCorner) {
        // edoc.force-corner : diagnostic uniquement (vide par defaut). Force la zone de decoupage
        // sur les grands plans et court-circuite la passe 1, pour isoler mecanisme vs localisation.
        return new TwoPassCartoucheExtractor(client, forcedCorner);
    }
}
