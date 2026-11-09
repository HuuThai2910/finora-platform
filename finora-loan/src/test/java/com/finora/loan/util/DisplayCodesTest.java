package com.finora.loan.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayCodesTest {

    @Test
    void applicationAndContractCodesFollowPrefixVietnamDateAndFourSafeCharacters() {
        Instant createdAt = Instant.parse("2026-11-09T03:00:00Z");

        assertThat(DisplayCodes.application(createdAt)).matches("HS-20261109-[ABCDEFGHJKMNPQRSTWXYZ2-9]{4}");
        assertThat(DisplayCodes.contract(createdAt)).matches("HĐ-20261109-[ABCDEFGHJKMNPQRSTWXYZ2-9]{4}");
    }

    @Test
    void dateIsTheVietnamDayNotTheUtcDay() {
        // 17:30 UTC ngày 08/11 là 00:30 ngày 09/11 giờ Việt Nam.
        assertThat(DisplayCodes.application(Instant.parse("2026-11-08T17:30:00Z"))).startsWith("HS-20261109-");
        // 16:59 UTC ngày 08/11 vẫn là 23:59 ngày 08/11 giờ Việt Nam.
        assertThat(DisplayCodes.application(Instant.parse("2026-11-08T16:59:59Z"))).startsWith("HS-20261108-");
    }

    @Test
    void alphabetHasNoLookAlikeCharacters() {
        assertThat(DisplayCodes.ALPHABET).doesNotContain("0", "O", "1", "I", "L", "U", "V");
        assertThat(DisplayCodes.ALPHABET.chars().distinct().count()).isEqualTo(DisplayCodes.ALPHABET.length());
    }

    @Test
    void everyAlphabetCharacterCanBeChosen() {
        Random random = new Random(42);
        Set<Character> seen = new HashSet<>();
        for (int index = 0; index < 2_000; index++) {
            String code = DisplayCodes.generate("HS", Instant.parse("2026-11-09T03:00:00Z"), random);
            code.substring(code.length() - DisplayCodes.RANDOM_LENGTH).chars().forEach(c -> seen.add((char) c));
        }
        assertThat(seen).hasSize(DisplayCodes.ALPHABET.length());
    }

    @Test
    void normalizeAcceptsUnaccentedContractPrefixAndLowerCase() {
        assertThat(DisplayCodes.normalize(" hd-20261109-k7qm ")).isEqualTo("HĐ-20261109-K7QM");
        assertThat(DisplayCodes.normalize("hđ-20261109-k7qm")).isEqualTo("HĐ-20261109-K7QM");
        assertThat(DisplayCodes.normalize("hs-20261109-3rxa")).isEqualTo("HS-20261109-3RXA");
        assertThat(DisplayCodes.normalize("LA-0123456789ABCDEF0123")).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(DisplayCodes.normalize("  ")).isNull();
    }
}
