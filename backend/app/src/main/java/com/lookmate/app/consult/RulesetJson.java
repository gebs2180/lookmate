package com.lookmate.app.consult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes the ruleset document. Its own mapper, so the stored JSON doesn't change with the app's settings. */
final class RulesetJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private RulesetJson() {
    }

    /** Parses a ruleset; a document that isn't one becomes {@link RulesetValidator.Invalid}. */
    static Ruleset read(String json) {
        try {
            Ruleset r = MAPPER.readValue(json, Ruleset.class);
            if (r == null) {
                throw new RulesetValidator.Invalid(java.util.List.of("The ruleset is empty."));
            }
            return r;
        } catch (JacksonException e) {
            throw new RulesetValidator.Invalid(java.util.List.of("This isn't ruleset JSON: " + e.getOriginalMessage()));
        }
    }

    /** Any value as JSON: a ruleset, or a session's stored answers. */
    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
