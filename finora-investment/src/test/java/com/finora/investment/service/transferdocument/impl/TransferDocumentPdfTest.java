package com.finora.investment.service.transferdocument.impl;

import com.finora.common.document.pades.EmbeddedPadesSignature;
import com.finora.common.document.pades.PadesCmsBuilder;
import com.finora.common.document.pades.PadesSignatureEmbedder;
import com.finora.common.document.pades.PadesSignaturePreparer;
import com.finora.common.document.pades.PadesSignatureVerifier;
import com.finora.investment.config.TransferDocumentProperties;
import com.finora.investment.service.transferdocument.TransferDocumentContent;
import com.finora.investment.service.transferdocument.TransferDocumentSeal;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferDocumentPdfTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:15:42Z");
    private static TransferDocumentSeal seal;
    private static PadesSignatureVerifier verifier;

    @BeforeAll
    static void setUp() {
        verifier = new PadesSignatureVerifier();
        seal = new TransferDocumentSealImpl(new PadesSignaturePreparer(), new PadesCmsBuilder(),
                new PadesSignatureEmbedder(), verifier, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rendersBothPartiesNotesAndAmounts() throws IOException {
        byte[] pdf = new TransferDocumentPdfRendererImpl().render(content(true));

        String text = text(pdf);
        assertThat(text).contains("GIẤY XÁC NHẬN CHUYỂN NHƯỢNG NOTE", "CN-TRD-0123456789ABCDEF0123",
                "Nguyễn Văn An", "********2345", "Trần Thị Bình", "Chưa cập nhật",
                "N-1001-01", "N-1001-02", "1.980.000 ₫", "99%", "HD-2026-0042",
                "NoteTransferDocument/TRD-0123456789ABCDEF0123", "CON DẤU DEMO");
    }

    @Test
    void escapesHtmlInPartyNames() throws IOException {
        TransferDocumentContent base = content(false);
        TransferDocumentContent content = new TransferDocumentContent(base.documentNumber(), base.tradeReference(),
                base.executedAt(), base.settledAt(), base.issuedAt(), base.loanApplicationId(), base.listingId(),
                base.contractNumber(), base.annualInterestRate(), base.termMonths(),
                new TransferDocumentContent.Party("7", "A <b>&</b> B", null), base.buyer(), base.pricePermille(),
                base.quantity(), base.amount(), base.platformFee(), base.sellerProceeds(), base.outstandingTotal(),
                base.defaulted(), base.notes(), base.demoSeal());

        String text = text(new TransferDocumentPdfRendererImpl().render(content));

        assertThat(text).contains("A <b>&</b> B").doesNotContain("CON DẤU DEMO");
    }

    @Test
    void sealAddsOneVerifiableFinoraSignatureWithoutChangingTheBase() {
        byte[] base = new TransferDocumentPdfRendererImpl().render(content(true));

        byte[] sealed = seal.seal(base, NOW);

        List<EmbeddedPadesSignature> signatures = verifier.verify(sealed);
        assertThat(signatures).hasSize(1);
        assertThat(signatures.get(0).subjectCommonName()).isEqualTo(TransferDocumentSealImpl.DEMO_COMMON_NAME);
        assertThat(seal.demo()).isTrue();
        // Incremental update: bản chưa ký là tiền tố nguyên vẹn của bản đã đóng dấu.
        assertThat(java.util.Arrays.copyOf(sealed, base.length)).isEqualTo(base);
    }

    @Test
    void documentHashIsLowercaseSha256Hex() {
        assertThat(TransferDocumentIssuerImpl.sha256Hex("abc".getBytes()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static TransferDocumentContent content(boolean demo) {
        return new TransferDocumentContent(
                "CN-TRD-0123456789ABCDEF0123", "TRD-0123456789ABCDEF0123",
                NOW.minusSeconds(120), NOW.minusSeconds(60), NOW,
                1001L, 55L, "HD-2026-0042", new BigDecimal("14.50"), 12,
                new TransferDocumentContent.Party("42", "Nguyễn Văn An", "********2345"),
                new TransferDocumentContent.Party("43", "Trần Thị Bình", null),
                990, 2,
                new BigDecimal("1980000.00"), new BigDecimal("9900.00"), new BigDecimal("1970100.00"),
                new BigDecimal("2000000.00"), false,
                List.of(
                        new TransferDocumentContent.NoteLine("N-1001-01", new BigDecimal("1000000.00"),
                                new BigDecimal("990000.00"), false),
                        new TransferDocumentContent.NoteLine("N-1001-02", new BigDecimal("1000000.00"),
                                new BigDecimal("990000.00"), false)),
                demo);
    }

    private static TransferDocumentProperties properties() {
        return new TransferDocumentProperties(true, null, 20, 8, null, null, null,
                new TransferDocumentProperties.Seal(null, null, null));
    }
}
