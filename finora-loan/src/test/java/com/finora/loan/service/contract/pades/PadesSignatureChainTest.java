package com.finora.loan.service.contract.pades;

import com.finora.loan.service.contract.pades.PadesTestSigners.TestSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.finora.loan.service.contract.pades.PadesTestSigners.newSigner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Chuỗi chữ ký PAdES trên PDF thật: mỗi chữ ký là một incremental update và không làm hỏng chữ ký trước. */
class PadesSignatureChainTest {

    private static TestSigner lenderOne;
    private static TestSigner lenderTwo;
    private static TestSigner borrower;

    private final PadesSignaturePreparer preparer = new PadesSignaturePreparer();
    private final PadesCmsBuilder cmsBuilder = new PadesCmsBuilder();
    private final PadesSignatureEmbedder embedder = new PadesSignatureEmbedder();
    private final PadesSignatureVerifier verifier = new PadesSignatureVerifier();

    @BeforeAll
    static void createSigners() throws Exception {
        lenderOne = newSigner("Test Lender One");
        lenderTwo = newSigner("Test Lender Two");
        borrower = newSigner("Test Borrower");
    }

    @Test
    void sequentialSignaturesAllVerifyAndEachByteRangeCoversItsWholeRevision() throws Exception {
        byte[] base = PadesTestSigners.simplePdf();
        byte[] first = sign(base, lenderOne, "Ben cho vay INVESTOR-001");
        byte[] second = sign(first, lenderTwo, "Ben cho vay INVESTOR-002");
        byte[] third = sign(second, borrower, "Ben vay BORROWER-001");

        // Incremental update: bản trước là tiền tố nguyên vẹn của bản sau.
        assertThat(Arrays.equals(third, 0, base.length, base, 0, base.length)).isTrue();
        assertThat(Arrays.equals(third, 0, first.length, first, 0, first.length)).isTrue();
        assertThat(Arrays.equals(third, 0, second.length, second, 0, second.length)).isTrue();

        List<EmbeddedPadesSignature> signatures = verifier.verify(third);
        assertThat(signatures).hasSize(3);
        assertThat(signatures).extracting(EmbeddedPadesSignature::revision).containsExactly(1, 2, 3);
        assertThat(signatures).extracting(EmbeddedPadesSignature::coveredLength)
                .containsExactly(first.length, second.length, third.length);
        assertThat(signatures).extracting(EmbeddedPadesSignature::revisionHash)
                .containsExactly(sha256Hex(first), sha256Hex(second), sha256Hex(third));
        assertThat(signatures).extracting(EmbeddedPadesSignature::certificate)
                .containsExactly(lenderOne.certificate(), lenderTwo.certificate(), borrower.certificate());
        for (EmbeddedPadesSignature signature : signatures) {
            int[] range = signature.byteRange();
            // ByteRange = [0, trước /Contents] + [sau /Contents, hết phiên bản]: chỉ đúng vùng chữ ký bị loại trừ.
            assertThat(range[0]).isZero();
            assertThat((long) range[2] + range[3]).isEqualTo(signature.coveredLength());
            assertThat(third[range[1]]).isEqualTo((byte) '<');
            assertThat(third[range[2] - 1]).isEqualTo((byte) '>');
        }
        // Bản cuối vẫn kiểm được cả ba chữ ký khi chỉ đọc lại tiền tố của từng phiên bản.
        assertThat(verifier.verify(first)).hasSize(1);
        assertThat(verifier.verify(second)).hasSize(2);

        try (PDDocument original = PDDocument.load(base); PDDocument signed = PDDocument.load(third)) {
            assertThat(signed.getSignatureDictionaries()).hasSize(3)
                    .allSatisfy(dictionary -> {
                        assertThat(dictionary.getFilter()).isEqualTo("Adobe.PPKLite");
                        assertThat(dictionary.getSubFilter()).isEqualTo("ETSI.CAdES.detached");
                    });
            // Chữ ký ẩn: số trang và nội dung hiển thị không đổi.
            assertThat(signed.getNumberOfPages()).isEqualTo(original.getNumberOfPages());
            assertThat(new PDFTextStripper().getText(signed)).isEqualTo(new PDFTextStripper().getText(original));
        }
    }

    @Test
    void cmsIsDetachedAndCarriesSignerCertificateAndSigningCertificateV2WithoutSigningTime() throws Exception {
        byte[] signed = sign(PadesTestSigners.simplePdf(), lenderOne, "Ben cho vay INVESTOR-001");

        try (PDDocument document = PDDocument.load(signed)) {
            PDSignature dictionary = document.getLastSignatureDictionary();
            byte[] contents = dictionary.getContents(signed);
            CMSSignedData cms = new CMSSignedData(
                    new CMSProcessableByteArray(dictionary.getSignedContent(signed)), contents);
            SignedData structure = SignedData.getInstance(cms.toASN1Structure().getContent());
            assertThat(structure.getEncapContentInfo().getContentType()).isEqualTo(CMSObjectIdentifiers.data);
            assertThat(structure.getEncapContentInfo().getContent()).isNull();

            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            assertThat(signer.getDigestAlgOID()).isEqualTo(NISTObjectIdentifiers.id_sha256.getId());
            X509CertificateHolder holder = (X509CertificateHolder) cms.getCertificates()
                    .getMatches(signer.getSID()).iterator().next();
            assertThat(holder.getEncoded()).isEqualTo(lenderOne.certificate().getEncoded());

            AttributeTable attributes = signer.getSignedAttributes();
            assertThat(attributes.get(CMSAttributes.signingTime)).isNull();
            Attribute contentType = attributes.get(CMSAttributes.contentType);
            assertThat(ASN1ObjectIdentifier.getInstance(contentType.getAttrValues().getObjectAt(0)))
                    .isEqualTo(CMSObjectIdentifiers.data);
            Attribute certificateAttribute = attributes.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2);
            assertThat(certificateAttribute).isNotNull();
            ESSCertIDv2 identifier = SigningCertificateV2.getInstance(
                    certificateAttribute.getAttrValues().getObjectAt(0)).getCerts()[0];
            assertThat(identifier.getHashAlgorithm().getAlgorithm()).isEqualTo(NISTObjectIdentifiers.id_sha256);
            assertThat(identifier.getCertHash())
                    .isEqualTo(MessageDigest.getInstance("SHA-256").digest(lenderOne.certificate().getEncoded()));
            assertThat(identifier.getIssuerSerial().getSerial().getValue())
                    .isEqualTo(lenderOne.certificate().getSerialNumber());
        }
    }

    @Test
    void changingAnEarlierRevisionBreaksItsSignature() throws Exception {
        byte[] signed = sign(sign(PadesTestSigners.simplePdf(), lenderOne, "A"), lenderTwo, "B");
        byte[] tampered = Arrays.copyOf(signed, signed.length);
        int textIndex = indexOf(tampered, "contract page 1".getBytes(StandardCharsets.US_ASCII));
        assertThat(textIndex).isPositive();
        tampered[textIndex] = (byte) 'C';

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(PadesSignatureException.class)
                .extracting("code")
                .isEqualTo("PADES_SIGNATURE_INVALID");
    }

    @Test
    void unsignedBytesAfterTheLastSignatureAreRejected() throws Exception {
        byte[] signed = sign(PadesTestSigners.simplePdf(), lenderOne, "A");
        byte[] appended = Arrays.copyOf(signed, signed.length + 12);
        System.arraycopy("\n% changed\n".getBytes(StandardCharsets.US_ASCII), 0, appended, signed.length, 11);

        assertThatThrownBy(() -> verifier.verify(appended))
                .isInstanceOf(PadesSignatureException.class)
                .hasMessageContaining("sau chữ ký cuối");
    }

    @Test
    void signatureFromAnotherKeyIsDetectedBeforeEmbedding() throws Exception {
        PreparedPadesRevision prepared = preparer.prepare(
                PadesTestSigners.simplePdf(), label("A"), Instant.now());
        byte[] attributes = cmsBuilder.signedAttributes(prepared.documentDigest(), lenderOne.certificate());

        assertThat(cmsBuilder.signatureMatches(attributes, lenderTwo.sign(attributes), lenderOne.certificate()))
                .isFalse();
        assertThat(cmsBuilder.signatureMatches(attributes, lenderOne.sign(attributes), lenderOne.certificate()))
                .isTrue();
    }

    @Test
    void embedderNeverOverwritesAnEmbeddedSignature() throws Exception {
        PreparedPadesRevision prepared = preparer.prepare(
                PadesTestSigners.simplePdf(), label("A"), Instant.now());
        byte[] attributes = cmsBuilder.signedAttributes(prepared.documentDigest(), lenderOne.certificate());
        byte[] cms = cmsBuilder.assemble(attributes, lenderOne.sign(attributes), lenderOne.certificate(), List.of());
        byte[] signed = embedder.embed(
                prepared.content(), prepared.contentsOffset(), prepared.contentsLength(), cms);

        assertThat(signed).hasSameSizeAs(prepared.content());
        assertThatThrownBy(() -> embedder.embed(
                signed, prepared.contentsOffset(), prepared.contentsLength(), cms))
                .isInstanceOf(PadesSignatureException.class)
                .extracting("code")
                .isEqualTo("PADES_ALREADY_EMBEDDED");
    }

    @Test
    void preparedRevisionKeepsTheBaseAsPrefixAndReservesAnEmptyPlaceholder() throws Exception {
        byte[] base = PadesTestSigners.simplePdf();
        Instant signingTime = Instant.parse("2026-10-17T03:15:42.789Z");

        PreparedPadesRevision prepared = preparer.prepare(base, label("A"), signingTime);

        byte[] content = prepared.content();
        assertThat(Arrays.equals(content, 0, base.length, base, 0, base.length)).isTrue();
        assertThat(prepared.contentsOffset()).isGreaterThanOrEqualTo(base.length);
        assertThat(prepared.contentsLength()).isEqualTo(2 * PadesSignaturePreparer.RESERVED_SIGNATURE_BYTES + 2);
        assertThat(prepared.signingTime()).isEqualTo(Instant.parse("2026-10-17T03:15:42Z"));
        assertThat(prepared.contentHash()).isEqualTo(sha256Hex(content));
    }

    private byte[] sign(byte[] base, TestSigner signer, String name) throws Exception {
        PreparedPadesRevision prepared = preparer.prepare(base, label(name), Instant.now());
        byte[] attributes = cmsBuilder.signedAttributes(prepared.documentDigest(), signer.certificate());
        byte[] signature = signer.sign(attributes);
        byte[] cms = cmsBuilder.assemble(attributes, signature, signer.certificate(), List.of());
        return embedder.embed(prepared.content(), prepared.contentsOffset(), prepared.contentsLength(), cms);
    }

    private static PadesSignatureLabel label(String name) {
        return new PadesSignatureLabel(name, "Ky hop dong FINORA", "FINORA");
    }

    private static String sha256Hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
