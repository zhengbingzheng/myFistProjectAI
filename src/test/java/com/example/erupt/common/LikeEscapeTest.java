package com.example.erupt.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikeEscapeTest {

    @Test
    @DisplayName("escape_percentAndUnderscoreEscaped")
    void escape_percentAndUnderscoreEscaped() {
        assertThat(LikeEscape.escape("50%off")).isEqualTo("50/%off");
        assertThat(LikeEscape.escape("a_b")).isEqualTo("a/_b");
    }

    @Test
    @DisplayName("escape_escapeCharItselfEscaped")
    void escape_escapeCharItselfEscaped() {
        assertThat(LikeEscape.escape("a/b")).isEqualTo("a//b");
    }

    @Test
    @DisplayName("escape_mixedWildcards")
    void escape_mixedWildcards() {
        assertThat(LikeEscape.escape("%_/%")).isEqualTo("/%/_///%");
    }

    @Test
    @DisplayName("escape_nullOrPlainKeepsAsIs")
    void escape_nullOrPlainKeepsAsIs() {
        assertThat(LikeEscape.escape(null)).isNull();
        assertThat(LikeEscape.escape("办公AI")).isEqualTo("办公AI");
        assertThat(LikeEscape.escape("")).isEmpty();
    }
}
