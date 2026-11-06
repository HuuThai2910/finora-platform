package com.finora.user.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileSpecificationsTest {

    @Test
    void plainAlphabetLinesUpWithAccentedOne() {
        assertThat(UserProfileSpecifications.VIETNAMESE_PLAIN)
                .hasSameSizeAs(UserProfileSpecifications.VIETNAMESE_ACCENTED)
                .matches("[aeiouyd]+");
    }

    @Test
    void searchTextIsLowercasedWithoutAccents() {
        assertThat(UserProfileSpecifications.normalizeSearchText("  Trương   Thị HUỆ "))
                .isEqualTo("truong thi hue");
        assertThat(UserProfileSpecifications.normalizeSearchText("Đặng Văn Đức")).isEqualTo("dang van duc");
    }
}
