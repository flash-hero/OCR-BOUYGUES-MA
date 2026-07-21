package com.bycn.edoc.smoke;

import com.bycn.edoc.ocr.CartoucheAnalysis;
import com.bycn.edoc.ocr.CartoucheExtraction;
import com.bycn.edoc.ocr.CartoucheField;
import com.bycn.edoc.ocr.MistralOcrClient;
import com.bycn.edoc.ocr.MistralOcrException;
import com.bycn.edoc.ocr.MistralOcrProperties;
import com.bycn.edoc.ocr.OcrResponseCache;
import com.bycn.edoc.ocr.TwoPassCartoucheExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Le « test décisif » d'ÉA1 : envoie chaque PDF de {@code data/samples/} à Mistral OCR et affiche,
 * pour un verdict humain, le mode de lecture (pleine page ou 2 passes pour les grands plans), les
 * paires libellé/valeur extraites et le JSON brut renvoyé.
 *
 * <p>Actif uniquement sous le profil {@code smoke} (voir {@code run_smoke.ps1}), de sorte que
 * {@code mvn test} et un démarrage normal ne déclenchent aucun appel réseau.</p>
 */
@Component
@Profile("smoke")
public class SmokeTestRunner implements CommandLineRunner {

    private static final String SEP = "--------------------------------------------------------";

    private final TwoPassCartoucheExtractor extractor;
    private final MistralOcrClient client;
    private final MistralOcrProperties props;
    private final OcrResponseCache cache;
    private final ObjectMapper prettyMapper;
    private final Path samplesDir;

    public SmokeTestRunner(TwoPassCartoucheExtractor extractor,
                           MistralOcrClient client,
                           MistralOcrProperties props,
                           OcrResponseCache cache,
                           ObjectMapper mapper,
                           @Value("${edoc.samples-dir:data/samples}") String samplesDir) {
        this.extractor = extractor;
        this.client = client;
        this.props = props;
        this.cache = cache;
        this.prettyMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.samplesDir = Path.of(samplesDir);
    }

    @Override
    public void run(String... args) {
        System.out.println();
        System.out.println("=== Test décisif Mistral OCR — extraction générique de cartouche ===");
        System.out.println("Modèle épinglé    : " + props.model());
        System.out.println("Échantillons/appel: " + props.samples()
                + " (consensus ; /v1/ocr n'a ni seed ni temperature)");
        System.out.println("Dossier d'exemples: " + samplesDir.toAbsolutePath());
        System.out.println("Cache réponses    : " + (cache.isEnabled()
                ? "actif (" + cache.directory().toAbsolutePath() + ") — un document déjà traité "
                        + "ne recoûte rien"
                : "désactivé"));
        System.out.println();

        if (!props.hasApiKey()) {
            System.out.println("[ARRET] Aucune cle API Mistral (MISTRAL_API_KEY). "
                    + "Renseigne-la dans .env puis relance. Voir check_setup.ps1.");
            return;
        }
        if (!Files.isDirectory(samplesDir)) {
            System.out.println("[ARRET] Dossier d'exemples introuvable : " + samplesDir.toAbsolutePath());
            return;
        }

        List<Path> pdfs = listPdfs();
        if (pdfs.isEmpty()) {
            System.out.println("[ARRET] Aucun PDF dans " + samplesDir.toAbsolutePath()
                    + ". Depose des PDF puis relance.");
            return;
        }

        int ok = 0;
        int failed = 0;
        int tiling = 0;
        int lowConfidence = 0;
        for (Path pdf : pdfs) {
            System.out.println(SEP);
            System.out.println("Document : " + pdf.getFileName() + "  (" + sizeKb(pdf) + " Ko)");
            try {
                // Couche fine de lecture : on lit le fichier d'exemple en octets, puis le cœur
                // (identique à ce que fera l'API REST sur un upload) travaille uniquement sur les octets.
                byte[] pdfBytes = Files.readAllBytes(pdf);
                CartoucheAnalysis analysis = extractor.extract(pdfBytes);
                printAnalysis(analysis);
                if (analysis.mode() == CartoucheAnalysis.Mode.NEEDS_TILING) {
                    tiling++;
                } else if (!analysis.qualityPassed()) {
                    lowConfidence++;
                }
                ok++;
            } catch (MistralOcrException e) {
                System.out.println("[ERREUR] " + e.getMessage());
                failed++;
            } catch (IOException e) {
                System.out.println("[ERREUR] Lecture du fichier impossible : " + e.getMessage());
                failed++;
            }
        }

        System.out.println();
        System.out.println("=== Bilan : " + ok + " traite(s), " + failed + " en erreur, "
                + tiling + " a decouper en tuiles, " + lowConfidence + " a verifier ===");
        if (cache.isEnabled()) {
            System.out.println("Cache : " + cache.hits() + " reponse(s) servie(s) depuis le cache "
                    + "(aucun cout API), " + cache.misses() + " requete(s) unique(s) manquante(s).");
        }
        System.out.println("Appels reseau reels : " + client.networkCalls()
                + " (chaque requete manquante = " + props.samples() + " echantillon(s)).");
        if (tiling > 0) {
            System.out.println("Note : " + tiling + " plan(s) dense(s) non localisable(s) en passe 1 "
                    + "(le redimensionnement est trop agressif meme pour une estimation grossiere). "
                    + "A signaler avant de generaliser : on basculera sur une approche par tuiles.");
        }
        if (lowConfidence > 0) {
            System.out.println("Note : " + lowConfidence + " plan(s) ou aucun coin n'a passe le controle "
                    + "qualite cartouche. Dernier recours a envisager : bords/bandes, puis tuiles.");
        }
    }

    private void printAnalysis(CartoucheAnalysis analysis) {
        switch (analysis.mode()) {
            case SINGLE_PAGE -> System.out.println("Mode           : lecture pleine page (format standard)");
            case TWO_PASS_CROP -> {
                System.out.println("Mode           : 2 passes (grand format) — zone retenue : "
                        + analysis.corner() + "  (coins evalues en parallele : " + analysis.attempts() + ")");
                if (!analysis.qualityPassed()) {
                    System.out.println("[A VERIFIER] Aucun coin n'a passe le controle qualite cartouche ; "
                            + "resultat le plus riche retenu, a valider par un humain.");
                }
            }
            case NEEDS_TILING -> {
                System.out.println("Mode           : 2 passes (grand format)");
                System.out.println("[LOCALISATION IMPOSSIBLE] Passe 1 renvoie '" + analysis.corner()
                        + "' : impossible de situer le cartouche, meme grossierement.");
                System.out.println("  -> Recommandation : decoupage en tuiles (voir bilan).");
                if (analysis.rawLocationAnnotation() != null) {
                    System.out.println("JSON brut (localisation, passe 1) :");
                    System.out.println(pretty(analysis.rawLocationAnnotation()));
                }
                return;
            }
        }

        CartoucheExtraction ex = analysis.extraction();
        System.out.println("cartoucheFound : " + ex.cartoucheFound());
        System.out.println("Paires libelle/valeur (" + ex.fields().size() + ") :");
        for (CartoucheField f : ex.fields()) {
            System.out.printf("   - %-28s : %s%n", f.label(), f.value());
        }
        System.out.println();
        System.out.println("JSON brut de l'annotation :");
        System.out.println(analysis.rawExtractionAnnotation() == null
                ? "(aucune annotation renvoyee)"
                : pretty(analysis.rawExtractionAnnotation()));
    }

    private List<Path> listPdfs() {
        try (Stream<Path> s = Files.list(samplesDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            System.out.println("[ERREUR] Lecture du dossier d'exemples : " + e.getMessage());
            return List.of();
        }
    }

    private String pretty(Object node) {
        try {
            return prettyMapper.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    private long sizeKb(Path pdf) {
        try {
            return Files.size(pdf) / 1024;
        } catch (IOException e) {
            return -1;
        }
    }
}
