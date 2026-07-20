package com.lianyu.service.square;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Smoke tests for franchise catalog registration (CL-064/065). */
class CharacterSquareCatalogTest {

    @Test
    void allSlugs_includesCoreAndFranchiseEntries() {
        List<String> slugs = CharacterSquareCatalog.allSlugs();
        assertTrue(slugs.size() >= 51, "expected full roster, got " + slugs.size());
        assertTrue(slugs.contains("ganyu"));
        assertTrue(slugs.contains("aru"));
        assertTrue(slugs.contains("kotori"));
        assertTrue(slugs.contains("emilia"));
        assertTrue(slugs.contains("zhongli"));
        assertTrue(slugs.contains("enoshima_junko"));
        assertTrue(slugs.contains("eriri"));
        assertTrue(new HashSet<>(slugs).size() == slugs.size(), "duplicate slug keys");
    }

    @Test
    void slugForSortOrder_mapsDecadeStepsToRoster() {
        assertEquals("ganyu", CharacterSquareCatalog.slugForSortOrder(10));
        assertEquals("noelle", CharacterSquareCatalog.slugForSortOrder(290));
        assertEquals("asahina_aoi", CharacterSquareCatalog.slugForSortOrder(510));
        assertEquals("ayaka", CharacterSquareCatalog.slugForSortOrder(520));
        assertEquals("elysia", CharacterSquareCatalog.slugForSortOrder(530));
        assertEquals("eriri", CharacterSquareCatalog.slugForSortOrder(540));
        assertNull(CharacterSquareCatalog.slugForSortOrder(550));
        assertNull(CharacterSquareCatalog.slugForSortOrder(0));
    }

    @Test
    void resolve_returnsLocalePackForEverySlug() {
        for (String slug : CharacterSquareCatalog.allSlugs()) {
            CharacterSquareCatalog.LocalePack pack = CharacterSquareCatalog.resolve(slug, "zh");
            assertNotNull(pack, "missing zh pack for " + slug);
            assertFalse(pack.name().isBlank(), "blank name for " + slug);
            assertFalse(pack.summary().isBlank(), "blank summary for " + slug);
            assertFalse(pack.prompt().isBlank(), "blank prompt for " + slug);
            assertFalse(pack.tags().isEmpty(), "empty tags for " + slug);
        }
    }

    @Test
    void resolve_fallsBackToZhForUnknownLangCode() {
        CharacterSquareCatalog.LocalePack pack = CharacterSquareCatalog.resolve("mika", "xx-INVALID");
        assertNotNull(pack);
        assertEquals("圣园未花", pack.name());
    }

    @Test
    void isKnownSlug_matchesAllSlugs() {
        Set<String> slugs = new HashSet<>(CharacterSquareCatalog.allSlugs());
        for (String slug : slugs) {
            assertTrue(CharacterSquareCatalog.isKnownSlug(slug));
        }
        assertTrue(CharacterSquareCatalog.isKnownSlug("not_a_real_slug"));
        assertFalse(CharacterSquareCatalog.isKnownSlug(""));
        assertFalse(CharacterSquareCatalog.isKnownSlug(null));
    }

    @Test
    void franchiseTagsAndLocalePack_helpersProduceValidEntries() {
        List<CharacterSquareCatalog.Tag> tags =
                CharacterSquareCatalog.franchiseTags("zh", "bluearchive", "genki");
        assertFalse(tags.isEmpty());

        CharacterSquareCatalog.LocalePack pack = CharacterSquareCatalog.localePack(
                "Test", "summary", tags, "prompt body");
        assertEquals("Test", pack.name());
        assertEquals("prompt body", pack.prompt());
    }
}
