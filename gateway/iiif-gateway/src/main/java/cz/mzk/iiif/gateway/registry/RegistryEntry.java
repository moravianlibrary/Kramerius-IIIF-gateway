/*
 * One library as the shared registry describes it.
 *
 * The registry carries far more than v1 used — it fetched this on every request and read five fields.
 * `name_en` is what makes a bilingual provider label free; `web` is the institution's own site, a better
 * homepage than the Kramerius host v1 used; `alive` lets a dead library fail fast with a clear message
 * instead of timing out.
 *
 * `name` is the institution's name in ITS OWN language, which is not always Czech: the registry lists
 * Slovak institutions (dikda, snk, cvti) and eod at uibk.ac.at. Tagging that `cs` states something false
 * that a viewer acts on, so the language tag travels with the name.
 */
package cz.mzk.iiif.gateway.registry;

import java.util.Optional;

public record RegistryEntry(
    String code,
    String name,
    Optional<String> nameEnglish,
    /** BCP 47 tag for `name`, inferred from the institution's country. */
    String nameLanguage,
    String url,
    Optional<String> version,
    boolean alive,
    Optional<String> logo,
    Optional<String> web,

    /** The web client serving this library — where its licence notices are published. */
    Optional<String> newClientUrl) {}
