package com.finora.payment.integration.bank.client;

import com.finora.payment.util.VietnameseNames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ngân hàng giả lập: kết quả tất định theo 4 số cuối để demo đủ mọi nhánh. */
class MockBankAccountProviderTest {

    private final MockBankAccountProvider provider = new MockBankAccountProvider();

    @Test
    void ownAccountCarriesTheEkycNameInBankStyle() {
        assertThat(provider.inquire("VCB", "0123456789", "Nguyễn Văn Đức"))
                .isEqualTo(BankAccountProvider.Inquiry.found("NGUYEN VAN DUC"));
    }

    @Test
    void suffixesSimulateEveryBankOutcome() {
        assertThat(provider.inquire("VCB", "1234560000", "A B").outcome())
                .isEqualTo(BankAccountProvider.Outcome.NOT_FOUND);
        assertThat(provider.inquire("VCB", "1234569999", "A B").outcome())
                .isEqualTo(BankAccountProvider.Outcome.LOCKED);
        assertThatThrownBy(() -> provider.inquire("VCB", "1234565555", "A B"))
                .isInstanceOf(BankAccountProvider.BankUnavailableException.class);
    }

    @Test
    void someoneElsesAccountHasAStableDifferentName() {
        String first = provider.inquire("VCB", "9876541111", "Nguyễn Văn Đức").holderName();
        assertThat(first).isEqualTo(provider.inquire("VCB", "9876541111", "Nguyễn Văn Đức").holderName());
        assertThat(first).matches("[A-Z]+ [A-Z]+ [A-Z]+");
        assertThat(VietnameseNames.sameName(first, "Nguyễn Văn Đức")).isFalse();
    }

    @Test
    void bankStyleNamesIgnoreDiacriticsCaseAndSpacing() {
        assertThat(VietnameseNames.bankStyle("  Trần   thị Ngọc-Ánh ")).isEqualTo("TRAN THI NGOC ANH");
        assertThat(VietnameseNames.sameName("ĐỖ ĐỨC ĐẠT", "do duc dat")).isTrue();
        assertThat(VietnameseNames.sameName("", "")).isFalse();
    }
}
