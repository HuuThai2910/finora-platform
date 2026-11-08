package com.finora.payment.service.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Danh bạ NAPAS đóng gói: đủ ngân hàng lớn, mã duy nhất, đọc được mã cũ của lệnh rút trước đây. */
class BankDirectoryTest {

    private final BankDirectory directory = new BankDirectory(new ObjectMapper());

    @Test
    void loadsTheBundledNapasSnapshot() {
        assertThat(directory.all()).hasSizeGreaterThan(40);
        assertThat(directory.all()).extracting(BankInfo::code)
                .contains("VCB", "ICB", "BIDV", "VBA", "TCB", "MB", "ACB", "VPB", "TPB", "STB")
                .doesNotHaveDuplicates()
                .doesNotContain("MOMO", "ZALOPAY");
        assertThat(directory.all()).allSatisfy(bank -> {
            assertThat(bank.bin()).matches("[0-9]{6}");
            assertThat(bank.logo()).startsWith("https://cdn.vietqr.io/img/");
        });
    }

    @Test
    void findsCaseInsensitivelyAndMapsLegacyCodes() {
        assertThat(directory.find(" vcb ")).map(BankInfo::shortName).hasValue("Vietcombank");
        assertThat(directory.find("CTG")).map(BankInfo::code).hasValue("ICB");
        assertThat(directory.find("AGR")).map(BankInfo::code).hasValue("VBA");
        assertThat(directory.find("XYZ")).isEmpty();
        assertThat(directory.displayName("XYZ")).isEqualTo("XYZ");
    }

    @Test
    void springCreatesTheBeanThroughTheObjectMapperConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class);
            context.register(BankDirectory.class);
            context.refresh();
            assertThat(context.getBean(BankDirectory.class).find("VCB")).isPresent();
        }
    }

    @Test
    void rejectsDuplicateCodes() {
        BankInfo bank = new BankInfo("VCB", "970436", "Vietcombank", "Ngân hàng", "https://cdn.vietqr.io/img/VCB.png");
        assertThatThrownBy(() -> new BankDirectory(List.of(bank, bank))).isInstanceOf(IllegalStateException.class);
    }
}
