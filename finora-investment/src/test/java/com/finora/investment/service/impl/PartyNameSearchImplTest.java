package com.finora.investment.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.orderbook.enums.TradeProgress;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.integration.user.client.UserIdentityClient;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tra ID theo họ tên qua User cho ô tìm của Chợ chuyển nhượng; kèm bảng chặng gộp của một lần khớp. */
class PartyNameSearchImplTest {

    private final UserIdentityClient client = mock(UserIdentityClient.class);
    private final PartyNameSearchImpl search = new PartyNameSearchImpl(client);

    @Test
    void trimmedQueryIsSentToUser() {
        when(client.userIdsByName("Trần An")).thenReturn(Set.of("42", "6f1c8a2e-0000-4000-8000-000000000042"));

        assertThat(search.idsMatching("  Trần An ")).containsExactlyInAnyOrder("42",
                "6f1c8a2e-0000-4000-8000-000000000042");
    }

    @Test
    void tooShortQueriesDoNotCallUser() {
        assertThat(search.idsMatching("A")).isEmpty();
        assertThat(search.idsMatching(" ")).isEmpty();
        assertThat(search.idsMatching(null)).isEmpty();
        verify(client, never()).userIdsByName(any());
    }

    @Test
    void userOutageIsA503InsteadOfAnEmptyResult() {
        when(client.userIdsByName("An Bình")).thenThrow(new UserIdentityClient.UserIdentityUnavailableException(
                "USER_IDENTITY_UNAVAILABLE", "down", null));

        assertThatThrownBy(() -> search.idsMatching("An Bình"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("USER_SEARCH_UNAVAILABLE");
    }

    @Test
    void tradeProgressFoldsFailuresIntoTheWaitingStage() {
        assertThat(TradeProgress.of(SettlementStatus.PENDING, null)).isEqualTo(TradeProgress.SETTLING);
        assertThat(TradeProgress.of(SettlementStatus.FAILED, null)).isEqualTo(TradeProgress.SETTLING);
        assertThat(TradeProgress.of(SettlementStatus.SETTLED, null)).isEqualTo(TradeProgress.DOC_PENDING);
        assertThat(TradeProgress.of(SettlementStatus.SETTLED, NoteTransferDocumentStatus.FAILED))
                .isEqualTo(TradeProgress.DOC_PENDING);
        assertThat(TradeProgress.of(SettlementStatus.SETTLED, NoteTransferDocumentStatus.ISSUED))
                .isEqualTo(TradeProgress.DONE);
    }
}
