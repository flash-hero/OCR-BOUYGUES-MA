package com.bycn.edoc.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bycn.edoc.classification.ClassificationProperties;
import com.bycn.edoc.classification.FieldClassifier;
import com.bycn.edoc.classification.FieldStatus;
import com.bycn.edoc.classification.SchemaFieldsRegistry;
import com.bycn.edoc.ocr.CartoucheAnalysis;
import com.bycn.edoc.ocr.CartoucheExtraction;
import com.bycn.edoc.ocr.CartoucheField;
import com.bycn.edoc.ocr.TwoPassCartoucheExtractor;
import com.bycn.edoc.validation.FieldValidator;
import com.bycn.edoc.validation.ReferenceTableRegistry;
import com.bycn.edoc.validation.ValidationProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Le pipeline complet vu depuis l'API, avec un extracteur simule : aucun appel reseau, aucun PDF.
 * On verifie ici la traduction demande generique -> P3/P4 -> reponse, pas la lecture OCR elle-meme
 * (deja couverte par TwoPassCartoucheExtractorTest).
 */
class ExtractionServiceTest {

    private static final byte[] DOCUMENT = "pdf-factice".getBytes(StandardCharsets.UTF_8);

    private static ExtractionService serviceReading(CartoucheField... pairs) {
        return serviceReading(new CartoucheExtraction(true, List.of(pairs)));
    }

    private static ExtractionService serviceReading(CartoucheExtraction extraction) {
        TwoPassCartoucheExtractor extractor = mock(TwoPassCartoucheExtractor.class);
        when(extractor.extract(any(), anyList())).thenReturn(CartoucheAnalysis.twoPassCrop(
                "bottom-right", extraction, null, null, true, 1));
        return serviceWith(extractor);
    }

    private static ExtractionService serviceWith(TwoPassCartoucheExtractor extractor) {
        SchemaFieldsRegistry registry = SchemaFieldsRegistry.fromClasspath(SchemaFieldsRegistry.DEFAULT_RESOURCE);
        ClassificationProperties classification = new ClassificationProperties(80, false);
        return new ExtractionService(
                extractor,
                new FieldClassifier(registry, classification),
                new FieldValidator(new ReferenceTableRegistry(), new ValidationProperties(85)),
                new SynonymEnricher(registry, classification, true));
    }

    private static ExtractedField fieldNamed(ExtractionResponse response, String name) {
        return response.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static RequestedField withValues(String name, List<String> labels, AllowedValue... values) {
        return new RequestedField(name, labels, List.of(values));
    }

    @Test
    void a_value_present_in_the_official_list_is_auto_validated() {
        // Arrange : champ au nom technique eDoc, valeurs officielles fournies par l'appelant.
        ExtractionService service = serviceReading(new CartoucheField("Phase", "EXE"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                withValues("spec_char1", List.of("Phase", "Pha"),
                        new AllowedValue("EXE", "Exécution"), new AllowedValue("DOE", "Dossier"))));

        // Act
        ExtractionResponse response = service.extract(DOCUMENT, request);

        // Assert
        ExtractedField phase = fieldNamed(response, "spec_char1");
        assertThat(phase.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(phase.value()).isEqualTo("EXE");
        assertThat(phase.referenceCode()).isEqualTo("EXE");
        assertThat(phase.referenceLabel()).isEqualTo("Exécution");
    }

    @Test
    void rule_d11_a_value_outside_the_official_list_is_reviewed_never_dropped() {
        ExtractionService service = serviceReading(new CartoucheField("Phase", "ZZZ"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                withValues("spec_char1", List.of("Phase"), new AllowedValue("EXE", "Exécution"))));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField phase = fieldNamed(response, "spec_char1");
        assertThat(phase.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(phase.value()).isEqualTo("ZZZ");
        assertThat(phase.referenceCode()).isNull();
    }

    @Test
    void a_free_field_without_official_values_is_returned_to_review() {
        // Champ libre cote eDoc (refTable vide) : rien a valider, jamais "valide par defaut".
        ExtractionService service = serviceReading(new CartoucheField("Bâtiment", "BtA"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                new RequestedField("spec_char5", List.of("Bâtiment"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField batiment = fieldNamed(response, "spec_char5");
        assertThat(batiment.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(batiment.value()).isEqualTo("BtA");
    }

    @Test
    void a_field_absent_from_the_cartouche_is_missing_and_left_empty() {
        ExtractionService service = serviceReading(new CartoucheField("Phase", "EXE"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                new RequestedField("spec_char8", List.of("Zone"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField zone = fieldNamed(response, "spec_char8");
        assertThat(zone.status()).isEqualTo(FieldStatus.MISSING);
        assertThat(zone.value()).isNull();
    }

    @Test
    void pairs_matching_no_requested_field_are_returned_never_silently_lost() {
        ExtractionService service = serviceReading(
                new CartoucheField("Phase", "EXE"), new CartoucheField("Echelle", "1/50"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                new RequestedField("spec_char1", List.of("Phase"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        assertThat(response.unclassifiedPairs())
                .containsExactly(new ExtractedPair("Echelle", "1/50"));
    }

    @Test
    void the_reading_metadata_is_propagated_so_the_caller_can_flag_a_doubtful_read() {
        // "Un controle qui dit oui ne veut pas dire que c'est correct" : l'appelant doit pouvoir
        // presenter le resultat comme incertain, donc recevoir mode / coin / controle qualite.
        TwoPassCartoucheExtractor extractor = mock(TwoPassCartoucheExtractor.class);
        when(extractor.extract(any(), anyList())).thenReturn(CartoucheAnalysis.twoPassCrop(
                "top-left", new CartoucheExtraction(true, List.of(new CartoucheField("Phase", "EXE"))),
                null, null, false, 4));

        ExtractionResponse response = serviceWith(extractor).extract(DOCUMENT,
                new ExtractionRequest("p", List.of(new RequestedField("f", List.of("Phase"), List.of()))));

        assertThat(response.mode()).isEqualTo("TWO_PASS_CROP");
        assertThat(response.corner()).isEqualTo("top-left");
        assertThat(response.qualityPassed()).isFalse();
        assertThat(response.cartoucheFound()).isTrue();
    }

    @Test
    void an_unlocatable_cartouche_yields_every_field_missing_without_failing() {
        TwoPassCartoucheExtractor extractor = mock(TwoPassCartoucheExtractor.class);
        when(extractor.extract(any(), anyList())).thenReturn(CartoucheAnalysis.needsTiling("unknown", null));

        ExtractionResponse response = serviceWith(extractor).extract(DOCUMENT,
                new ExtractionRequest("p", List.of(new RequestedField("f", List.of("Phase"), List.of()))));

        assertThat(response.cartoucheFound()).isFalse();
        assertThat(response.mode()).isEqualTo("NEEDS_TILING");
        assertThat(fieldNamed(response, "f").status()).isEqualTo(FieldStatus.MISSING);
    }

    @Test
    void fields_are_returned_in_the_order_they_were_requested() {
        ExtractionService service = serviceReading(
                new CartoucheField("Phase", "EXE"), new CartoucheField("Lot", "03"));
        ExtractionRequest request = new ExtractionRequest("240716tdr", List.of(
                new RequestedField("z_lot", List.of("Lot"), List.of()),
                new RequestedField("a_phase", List.of("Phase"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        assertThat(response.fields()).extracting(ExtractedField::name)
                .containsExactly("z_lot", "a_phase");
    }

    @Test
    void a_model_assignment_fills_a_field_that_string_distance_alone_would_miss() {
        // « NUMERO DE DOCUMENT » ne ressemble pas assez à « Numéro » en chaîne entière : c'est le
        // rattachement proposé par le modèle qui doit sauver le champ — cas signalé en conditions réelles.
        ExtractionService service = serviceReading(new CartoucheExtraction(true,
                List.of(new CartoucheField("NUMERO DE DOCUMENT", "PRORFR120"),
                        new CartoucheField("Phase", "EXE")),
                List.of(new com.bycn.edoc.ocr.CartoucheAssignment(
                        "numero", "NUMERO DE DOCUMENT", "PRORFR120"))));
        ExtractionRequest request = new ExtractionRequest("p", List.of(
                new RequestedField("numero", List.of("Numéro"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField numero = fieldNamed(response, "numero");
        assertThat(numero.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(numero.value()).isEqualTo("PRORFR120");
        assertThat(numero.rawLabel()).isEqualTo("NUMERO DE DOCUMENT");
        // La paire rattachée ne ressort pas non plus en « non classée ».
        assertThat(response.unclassifiedPairs()).extracting(ExtractedPair::label)
                .doesNotContain("NUMERO DE DOCUMENT");
    }

    @Test
    void a_model_assignment_onto_an_unrelated_label_is_ignored_contresens_guard() {
        // Mesuré en conditions réelles : le modèle a proposé « PROJET = FUTUR PALAIS DE JUSTICE DE
        // PARIS » pour le champ Phase. La valeur est bien lue — la garde anti-invention la laisse
        // donc passer — mais le libellé n'a rien à voir avec le champ. Le champ doit rester vide
        // plutôt que de recevoir la valeur d'un autre champ.
        ExtractionService service = serviceReading(new CartoucheExtraction(true,
                List.of(new CartoucheField("PROJET", "FUTUR PALAIS DE JUSTICE DE PARIS")),
                List.of(new com.bycn.edoc.ocr.CartoucheAssignment(
                        "phase", "PROJET", "FUTUR PALAIS DE JUSTICE DE PARIS"))));
        ExtractionRequest request = new ExtractionRequest("p", List.of(
                new RequestedField("phase", List.of("Phase"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        assertThat(fieldNamed(response, "phase").status()).isEqualTo(FieldStatus.MISSING);
        // La paire refusée n'est pas perdue : elle ressort en non classée.
        assertThat(response.unclassifiedPairs()).extracting(ExtractedPair::label).contains("PROJET");
    }

    @Test
    void a_model_assignment_whose_value_was_never_read_is_ignored_hallucination_guard() {
        // La proposition porte une valeur absente des paires lues : on refuse de la croire, et le
        // champ repasse par la classification floue (ici : rien ne matche → MISSING, champ vide).
        ExtractionService service = serviceReading(new CartoucheExtraction(true,
                List.of(new CartoucheField("Phase", "EXE")),
                List.of(new com.bycn.edoc.ocr.CartoucheAssignment("numero", "N° Doc", "INVENTÉ-123"))));
        ExtractionRequest request = new ExtractionRequest("p", List.of(
                new RequestedField("numero", List.of("Numéro"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField numero = fieldNamed(response, "numero");
        assertThat(numero.status()).isEqualTo(FieldStatus.MISSING);
        assertThat(numero.value()).isNull();
    }

    @Test
    void an_assigned_value_still_goes_through_official_value_validation() {
        // Le rattachement du modèle ne court-circuite jamais P4 : la valeur rattachée est validée
        // contre la liste officielle comme n'importe quelle autre.
        ExtractionService service = serviceReading(new CartoucheExtraction(true,
                List.of(new CartoucheField("Phase du projet", "EXE")),
                List.of(new com.bycn.edoc.ocr.CartoucheAssignment("spec_char1", "Phase du projet", "EXE"))));
        ExtractionRequest request = new ExtractionRequest("p", List.of(
                withValues("spec_char1", List.of("Phase"), new AllowedValue("EXE", "Exécution"))));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        ExtractedField phase = fieldNamed(response, "spec_char1");
        assertThat(phase.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(phase.referenceCode()).isEqualTo("EXE");
    }

    @Test
    void a_pair_taken_by_an_assignment_is_not_offered_again_to_fuzzy_classification() {
        // Deux valeurs « A » : celle d'Indice (rattachée par le modèle) et celle de Zone. Le champ
        // zone doit recevoir SA paire, pas voler celle déjà consommée par le rattachement.
        ExtractionService service = serviceReading(new CartoucheExtraction(true,
                List.of(new CartoucheField("Ind.", "A"), new CartoucheField("Zone", "A")),
                List.of(new com.bycn.edoc.ocr.CartoucheAssignment("indice", "Ind.", "A"))));
        ExtractionRequest request = new ExtractionRequest("p", List.of(
                new RequestedField("indice", List.of("Indice"), List.of()),
                new RequestedField("zone", List.of("Zone"), List.of())));

        ExtractionResponse response = service.extract(DOCUMENT, request);

        assertThat(fieldNamed(response, "indice").rawLabel()).isEqualTo("Ind.");
        assertThat(fieldNamed(response, "zone").rawLabel()).isEqualTo("Zone");
        assertThat(response.unclassifiedPairs()).isEmpty();
    }
}
