package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfArtifactType;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.dto.contract.response.AdminContractPartyResponse;
import com.finora.loan.dto.contract.response.AdminLoanContractResponse;
import com.finora.loan.integration.signature.client.SignatureProvider;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.service.contract.LoanContractPdfContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hợp đồng cho màn quản trị: nhà đầu tư trước (góp nhiều trước), người vay ký cuối; hạn ký và lần phát hành theo văn
 * bản hiện hành; nhà cung cấp chữ ký số theo bên đã ký hoặc cấu hình.
 */
class AdminLoanContractServiceImplTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T02:00:00Z");
    private static final Instant REISSUED = Instant.parse("2026-11-05T02:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-12T02:00:00Z");

    private final LoanContractRepository loanContractRepository = mock(LoanContractRepository.class);
    private final ContractPartyRepository contractPartyRepository = mock(ContractPartyRepository.class);
    private final LoanContractDocumentRepository documentRepository = mock(LoanContractDocumentRepository.class);
    private final LoanContractStatusHistoryRepository historyRepository = mock(LoanContractStatusHistoryRepository.class);
    private final FinoraLoanRepository finoraLoanRepository = mock(FinoraLoanRepository.class);
    private final SignatureProvider signatureProvider = mock(SignatureProvider.class);
    private AdminLoanContractServiceImpl service;
    private LoanContract contract;

    @BeforeEach
    void setUp() {
        when(signatureProvider.type()).thenReturn(SignatureProviderType.VNPT_SMART_CA);
        LoanContractProperties properties = mock(LoanContractProperties.class);
        when(properties.signatureWindow()).thenReturn(Duration.ofDays(4));
        service = new AdminLoanContractServiceImpl(loanContractRepository, contractPartyRepository, documentRepository,
                historyRepository, finoraLoanRepository, signatureProvider, properties);

        contract = mock(LoanContract.class);
        when(contract.getId()).thenReturn(9L);
        when(contract.getApplicationId()).thenReturn(3L);
        when(contract.getContractNumber()).thenReturn("LC-1");
        when(contract.getContractCode()).thenReturn("HĐ-20261101-K7QM");
        when(contract.getBorrowerId()).thenReturn("borrower-1");
        when(contract.isMultiParty()).thenReturn(true);
        when(contract.getStatus()).thenReturn(LoanContractStatus.PENDING_LENDER_SIGNATURES);
        when(contract.getPrincipalAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(contract.getDocumentHash()).thenReturn("d".repeat(64));
        when(contract.getExpiresAt()).thenReturn(EXPIRES);
        when(contract.getCreatedAt()).thenReturn(CREATED);
        when(loanContractRepository.findByContractNumber("LC-1")).thenReturn(Optional.of(contract));
        when(finoraLoanRepository.findByLoanApplicationId(3L)).thenReturn(Optional.empty());
    }

    @Test
    void lendersComeFirstByAmountAndBorrowerSignsLast() {
        ContractParty small = lender("inv-small", 11L, "3000000.00", ContractPartyStatus.SIGNED,
                SignatureProviderType.VNPT_SMART_CA, Instant.parse("2026-11-02T03:00:00Z"));
        ContractParty large = lender("inv-large", 12L, "7000000.00", ContractPartyStatus.PENDING_SIGNATURE, null, null);
        ContractParty borrowerRow = mock(ContractParty.class);
        when(borrowerRow.getPartyType()).thenReturn(ContractPartyType.BORROWER);
        when(contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(9L))
                .thenReturn(List.of(borrowerRow, small, large));
        when(historyRepository.findIssueTimes(9L, LoanContractStatus.PENDING_REFUNDING,
                LoanContractStatus.PENDING_LENDER_SIGNATURES))
                .thenReturn(List.of(REISSUED, CREATED));
        LoanContractDocument signable = pdf("p".repeat(64));
        when(documentRepository.findByContractIdAndArtifactType(9L, ContractPdfArtifactType.SIGNABLE))
                .thenReturn(Optional.of(signable));

        AdminLoanContractResponse response = service.detail("LC-1");

        assertThat(response.parties()).extracting(AdminContractPartyResponse::partyId)
                .containsExactly("inv-large", "inv-small", "borrower-1");
        AdminContractPartyResponse borrower = response.parties().get(2);
        assertThat(borrower.partyType()).isEqualTo(ContractPartyType.BORROWER);
        assertThat(borrower.status()).isEqualTo(ContractPartyStatus.PENDING_SIGNATURE);
        assertThat(borrower.allocationAmount()).isEqualByComparingTo("10000000.00");
        // Văn bản phát hành lại sau gọi vốn bổ sung: lần phát hành là lần mới nhất, hạn ký của văn bản đó.
        assertThat(response.issuedAt()).isEqualTo(REISSUED);
        assertThat(response.expiresAt()).isEqualTo(EXPIRES);
        assertThat(response.documentHash()).isEqualTo("d".repeat(64));
        assertThat(response.pdfDocumentHash()).isEqualTo("p".repeat(64));
        assertThat(response.signatureProvider()).isEqualTo(SignatureProviderType.VNPT_SMART_CA);
        assertThat(response.contractCode()).isEqualTo("HĐ-20261101-K7QM");
        assertThat(response.loanNumber()).isNull();
    }

    @Test
    void signedBorrowerAndProviderComeFromTheContract() {
        Instant signedAt = Instant.parse("2026-11-03T03:00:00Z");
        when(contract.getStatus()).thenReturn(LoanContractStatus.EFFECTIVE);
        when(contract.getSignedAt()).thenReturn(signedAt);
        when(contract.getSignatureProvider()).thenReturn(SignatureProviderType.MOCK);
        when(contract.getEffectiveAt()).thenReturn(signedAt);
        when(contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(9L)).thenReturn(List.of());
        when(historyRepository.findIssueTimes(any(), any(), any())).thenReturn(List.of());
        LoanContractDocument signedPdf = pdf("s".repeat(64));
        when(documentRepository.findByContractIdAndArtifactType(9L, ContractPdfArtifactType.SIGNED_PDF))
                .thenReturn(Optional.of(signedPdf));
        FinoraLoan loan = mock(FinoraLoan.class);
        when(loan.getLoanNumber()).thenReturn("LN-1");
        when(finoraLoanRepository.findByLoanApplicationId(3L)).thenReturn(Optional.of(loan));

        AdminLoanContractResponse response = service.detail("LC-1");

        assertThat(response.parties()).singleElement().satisfies(borrower -> {
            assertThat(borrower.status()).isEqualTo(ContractPartyStatus.SIGNED);
            assertThat(borrower.signedAt()).isEqualTo(signedAt);
        });
        assertThat(response.signatureProvider()).isEqualTo(SignatureProviderType.MOCK);
        assertThat(response.pdfDocumentHash()).isEqualTo("s".repeat(64));
        assertThat(response.issuedAt()).isEqualTo(CREATED);
        assertThat(response.loanNumber()).isEqualTo("LN-1");
    }

    @Test
    void borrowerDeclineIsShownOnTheBorrowerRow() {
        Instant declinedAt = Instant.parse("2026-11-04T03:00:00Z");
        when(contract.getStatus()).thenReturn(LoanContractStatus.DECLINED);
        when(contract.getDeclinedAt()).thenReturn(declinedAt);
        when(contract.getDeclinedBy()).thenReturn("borrower-1");
        when(contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(9L)).thenReturn(List.of());
        when(historyRepository.findIssueTimes(any(), any(), any())).thenReturn(List.of());

        AdminContractPartyResponse borrower = service.detail("LC-1").parties().get(0);

        assertThat(borrower.status()).isEqualTo(ContractPartyStatus.DECLINED);
        assertThat(borrower.closedAt()).isEqualTo(declinedAt);
    }

    @Test
    void documentUsesDisplayCodeAsFileNameAnd404WithoutPdf() {
        LoanContractDocument signable = pdf("p".repeat(64));
        when(documentRepository.findByContractIdAndArtifactType(9L, ContractPdfArtifactType.SIGNABLE))
                .thenReturn(Optional.of(signable));

        LoanContractPdfContent document = service.document("LC-1");

        assertThat(document.fileName()).isEqualTo("HĐ-20261101-K7QM.pdf");
        assertThat(document.contentHash()).isEqualTo("p".repeat(64));

        when(documentRepository.findByContractIdAndArtifactType(9L, ContractPdfArtifactType.SIGNABLE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.document("LC-1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.detail("LC-404")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void signingPolicyReportsConfiguredProviderAndWindow() {
        assertThat(service.signingPolicy().signatureProvider()).isEqualTo(SignatureProviderType.VNPT_SMART_CA);
        assertThat(service.signingPolicy().signatureWindowHours()).isEqualTo(96);
    }

    private static ContractParty lender(String partyId, Long commitmentId, String amount, ContractPartyStatus status,
            SignatureProviderType provider, Instant signedAt) {
        ContractParty party = mock(ContractParty.class);
        when(party.getPartyType()).thenReturn(ContractPartyType.LENDER);
        when(party.getPartyId()).thenReturn(partyId);
        when(party.getCommitmentId()).thenReturn(commitmentId);
        when(party.getAllocationAmount()).thenReturn(new BigDecimal(amount));
        when(party.getStatus()).thenReturn(status);
        when(party.getSignatureProvider()).thenReturn(provider);
        when(party.getSignedAt()).thenReturn(signedAt);
        return party;
    }

    private static LoanContractDocument pdf(String hash) {
        LoanContractDocument document = mock(LoanContractDocument.class);
        when(document.getContentHash()).thenReturn(hash);
        when(document.getContentType()).thenReturn(LoanContractDocument.PDF_CONTENT_TYPE);
        when(document.contentCopy()).thenReturn(new byte[]{1, 2});
        return document;
    }
}
