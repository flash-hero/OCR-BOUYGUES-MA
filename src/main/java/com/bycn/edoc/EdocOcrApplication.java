package com.bycn.edoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée de l'application eDoc OCR.
 *
 * <p>ÉA1 (cette session) : uniquement le socle Spring Boot + le client Mistral OCR
 * et le test décisif d'extraction générique de cartouche. Aucune API REST, aucune
 * classification/validation, aucun Tesseract à ce stade.</p>
 *
 */
@SpringBootApplication
public class EdocOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdocOcrApplication.class, args);
    }
}
