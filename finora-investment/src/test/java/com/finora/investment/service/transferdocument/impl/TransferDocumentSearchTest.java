package com.finora.investment.service.transferdocument.impl;

import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ô tìm của bảng giấy xác nhận chuyển nhượng: số giấy, mã lần khớp hoặc mã hồ sơ/hợp đồng. */
@ExtendWith(MockitoExtension.class)
class TransferDocumentSearchTest {

    @Mock
    private NoteTransferDocumentRepository noteTransferDocumentRepository;
    @InjectMocks
    private TransferDocumentAdminServiceImpl service;

    @Test
    void searchKeepsStatusFilterAndUsesCodePattern() {
        when(noteTransferDocumentRepository.searchRows(eq(NoteTransferDocumentStatus.FAILED), eq("%GXN-2026%"), any()))
                .thenReturn(Page.empty());

        service.list(NoteTransferDocumentStatus.FAILED, "gxn-2026", PageRequest.of(0, 20));

        verify(noteTransferDocumentRepository)
                .searchRows(eq(NoteTransferDocumentStatus.FAILED), eq("%GXN-2026%"), any());
    }
}
