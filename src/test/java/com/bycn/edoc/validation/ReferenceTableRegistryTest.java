package com.bycn.edoc.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests sur les VRAIES tables livrees dans projects/mtbc_buche/. Aucun appel reseau. */
class ReferenceTableRegistryTest {

    private static final String PROJECT = "mtbc_buche";

    @Test
    void an_existing_csv_is_loaded_with_its_codes_and_labels() {
        List<ReferenceEntry> phase = new ReferenceTableRegistry().getTable(PROJECT, "PHASE");

        assertThat(phase).containsExactly(
                new ReferenceEntry("AVP", "Avant Projet"),
                new ReferenceEntry("DOE", "Dossier des Ouvrages Exécutées"),
                new ReferenceEntry("EXE", "Exécution"),
                new ReferenceEntry("PRO", "Projet"));
    }

    @Test
    void labels_containing_commas_are_parsed_as_a_single_quoted_field() {
        // "Désamiantage, dépollution, déplombage" : la raison d'utiliser un vrai parseur CSV.
        List<ReferenceEntry> lot = new ReferenceTableRegistry().getTable(PROJECT, "LOT");

        assertThat(lot).contains(new ReferenceEntry("01", "Désamiantage, dépollution, déplombage"));
        assertThat(lot).hasSize(34);
    }

    @Test
    void a_field_without_a_csv_file_yields_an_empty_table_not_an_exception() {
        // C'est ce contrat qui fait de l'ABSENCE de fichier le mecanisme de pass-through.
        assertThat(new ReferenceTableRegistry().getTable(PROJECT, "EMETTEUR")).isEmpty();
        assertThat(new ReferenceTableRegistry().getTable(PROJECT, "NUMERO")).isEmpty();
        assertThat(new ReferenceTableRegistry().getTable(PROJECT, "Titre1")).isEmpty();
    }

    @Test
    void an_unknown_project_yields_an_empty_table() {
        assertThat(new ReferenceTableRegistry().getTable("projet_inexistant", "PHASE")).isEmpty();
    }

    @Test
    void a_path_traversal_segment_is_treated_as_no_table() {
        // projectCode et targetField viennent de l'appel API : jamais interpoles sans filtre.
        ReferenceTableRegistry registry = new ReferenceTableRegistry();

        assertThat(registry.getTable("../../secrets", "PHASE")).isEmpty();
        assertThat(registry.getTable(PROJECT, "../../application")).isEmpty();
        assertThat(registry.getTable(null, "PHASE")).isEmpty();
        assertThat(registry.getTable(PROJECT, null)).isEmpty();
    }

    @Test
    void a_table_is_loaded_once_then_served_from_the_cache() {
        ReferenceTableRegistry registry = new ReferenceTableRegistry();

        List<ReferenceEntry> first = registry.getTable(PROJECT, "ZONE");
        List<ReferenceEntry> second = registry.getTable(PROJECT, "ZONE");

        assertThat(second).isSameAs(first);
    }

    @Test
    void the_five_delivered_tables_are_all_readable() {
        ReferenceTableRegistry registry = new ReferenceTableRegistry();

        assertThat(registry.getTable(PROJECT, "PHASE")).hasSize(4);
        assertThat(registry.getTable(PROJECT, "NIVEAU")).hasSize(7);
        assertThat(registry.getTable(PROJECT, "ZONE")).hasSize(5);
        assertThat(registry.getTable(PROJECT, "LOT")).hasSize(34);
        assertThat(registry.getTable(PROJECT, "TYPE")).hasSize(32);
    }

    @Test
    void a_malformed_header_fails_loudly_instead_of_passing_through_silently() {
        // Un fichier present mais illisible est une erreur de configuration reelle : la masquer en
        // table vide ferait passer le champ pour « sans table », ce qui est faux.
        assertThatThrownBy(() -> ReferenceTableRegistry.parse("colonne_inattendue\nAVP\n", "test.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code");
    }

    @Test
    void a_utf8_byte_order_mark_does_not_break_the_header() {
        List<ReferenceEntry> entries = ReferenceTableRegistry.parse("﻿code,libelle\nEXE,Exécution\n", "test.csv");

        assertThat(entries).containsExactly(new ReferenceEntry("EXE", "Exécution"));
    }

    @Test
    void a_row_without_a_code_is_ignored() {
        List<ReferenceEntry> entries = ReferenceTableRegistry.parse("code,libelle\n,Sans code\nEXE,Exécution\n", "test.csv");

        assertThat(entries).containsExactly(new ReferenceEntry("EXE", "Exécution"));
    }
}
