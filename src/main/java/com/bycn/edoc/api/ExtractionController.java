package com.bycn.edoc.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * P5 — l'API REST du moteur : « voici un document et les champs de mon projet, remplis-les ».
 *
 * <p>Un seul point d'entrée, synchrone. Une extraction dure de 5 à 28 s selon la densité du
 * document (profil mesuré, voir {@code AGENT_CONTEXT.md} §2bis) : c'est à l'appelant de ne pas
 * bloquer son utilisateur pendant ce temps.</p>
 *
 * <p>La partie {@code request} est reçue en texte puis désérialisée explicitement, plutôt que via
 * un {@code @RequestPart} typé : un client multipart n'étiquette pas toujours ses parties en
 * {@code application/json}, et un échec de négociation de contenu produirait une erreur bien plus
 * obscure qu'un message de parsing clair.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ExtractionController {

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ExtractionService service;
    private final ObjectMapper mapper;

    public ExtractionController(ExtractionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/extractions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtractionResponse extract(@RequestPart("file") MultipartFile file,
                                      @RequestPart("request") String requestJson) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadExtractionRequestException("La partie « file » est absente ou vide.");
        }
        ExtractionRequest request = parse(requestJson);

        ExtractionResponse response = service.extract(file.getBytes(), request);
        log.info("Extraction projet={} fichier={} ({} o) : cartouche={} mode={} champs={} en {} ms",
                request.projectCode(), file.getOriginalFilename(), file.getSize(),
                response.cartoucheFound(), response.mode(), response.fields().size(),
                response.durationMs());
        return response;
    }

    private ExtractionRequest parse(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            throw new BadExtractionRequestException("La partie « request » est absente ou vide.");
        }
        try {
            ExtractionRequest request = mapper.readValue(requestJson, ExtractionRequest.class);
            if (request == null) {
                throw new BadExtractionRequestException("La partie « request » ne contient pas d'objet JSON.");
            }
            return request;
        } catch (IOException e) {
            throw new BadExtractionRequestException("La partie « request » est un JSON illisible : " + e.getMessage());
        }
    }
}
