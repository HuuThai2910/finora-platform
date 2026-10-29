package com.finora.loan.service.contract.pades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Khóa và chứng thư sinh ngay trong test; không có khóa riêng hay keystore nào được commit. */
final class PadesTestSigners {

    private PadesTestSigners() {
    }

    record TestSigner(PrivateKey privateKey, X509Certificate certificate) {

        /** Ký như provider "ký hash": SHA256withRSA trên DER signed attributes. */
        byte[] sign(byte[] signedAttributes) throws Exception {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signedAttributes);
            return signature.sign();
        }
    }

    static TestSigner newSigner(String commonName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=" + commonName + ", O=FINORA Test, C=VN");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(Math.abs(new SecureRandom().nextLong()) + 1),
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(30))),
                subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
        return new TestSigner(keyPair.getPrivate(), certificate);
    }

    /** PDF ba trang tối giản do PDFBox ghi (xref dạng bảng giống bản openhtmltopdf sinh ra). */
    static byte[] simplePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 1; index <= 3; index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                // Không nén content stream để test sửa được đúng một ký tự văn bản trong bytes của bản đã ký.
                try (PDPageContentStream stream = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText("FINORA test contract page " + index);
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
