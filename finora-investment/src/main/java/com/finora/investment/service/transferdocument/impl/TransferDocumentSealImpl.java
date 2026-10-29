package com.finora.investment.service.transferdocument.impl;

import com.finora.common.document.pades.PadesCmsBuilder;
import com.finora.common.document.pades.PadesSignatureEmbedder;
import com.finora.common.document.pades.PadesSignatureException;
import com.finora.common.document.pades.PadesSignatureLabel;
import com.finora.common.document.pades.PadesSignaturePreparer;
import com.finora.common.document.pades.PadesSignatureVerifier;
import com.finora.common.document.pades.PreparedPadesRevision;
import com.finora.investment.config.TransferDocumentProperties;
import com.finora.investment.service.transferdocument.TransferDocumentSeal;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Con dấu số của FINORA trên giấy xác nhận chuyển nhượng: một chữ ký PAdES-B-B ẩn do chính nền tảng ký, ký ngay trong
 * tiến trình (không có bước xác nhận của người dùng như hợp đồng vay).
 *
 * <p>Mặc định dùng chứng thư demo tự ký sinh lúc khởi động, chỉ nằm trong bộ nhớ, subject ghi rõ "không có giá trị
 * pháp lý" giống chế độ MOCK của Loan. Văn bản cũ vẫn kiểm được sau khi khởi động lại vì chứng thư được nhúng trong
 * CMS. Môi trường thật cấu hình keystore PKCS#12 của con dấu tổ chức.</p>
 */
@Slf4j
@Component
public class TransferDocumentSealImpl implements TransferDocumentSeal {

    static final String DEMO_COMMON_NAME = "FINORA DEMO - KHONG CO GIA TRI PHAP LY";
    private static final int DEMO_KEY_SIZE = 2048;
    private static final PadesSignatureLabel LABEL = new PadesSignatureLabel(
            "FINORA", "Xác nhận chuyển nhượng Note", "FINORA, Việt Nam");

    private final PadesSignaturePreparer preparer;
    private final PadesCmsBuilder cmsBuilder;
    private final PadesSignatureEmbedder embedder;
    private final PadesSignatureVerifier verifier;
    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final List<X509Certificate> chain;
    private final boolean demo;

    public TransferDocumentSealImpl(
            PadesSignaturePreparer preparer,
            PadesCmsBuilder cmsBuilder,
            PadesSignatureEmbedder embedder,
            PadesSignatureVerifier verifier,
            TransferDocumentProperties properties,
            Clock clock
    ) {
        this.preparer = preparer;
        this.cmsBuilder = cmsBuilder;
        this.embedder = embedder;
        this.verifier = verifier;
        TransferDocumentProperties.Seal seal = properties.seal();
        Credential credential = seal.configured() ? load(seal) : demo(clock.instant());
        this.privateKey = credential.privateKey();
        this.certificate = credential.certificate();
        this.chain = credential.chain();
        this.demo = !seal.configured();
        log.info("Con dấu giấy xác nhận chuyển nhượng: subject={}, demo={}", subjectCommonName(), demo);
    }

    @Override
    public boolean demo() {
        return demo;
    }

    @Override
    public String subjectCommonName() {
        return certificate.getSubjectX500Principal().getName();
    }

    @Override
    public byte[] seal(byte[] pdf, Instant now) {
        PreparedPadesRevision prepared = preparer.prepare(pdf, LABEL, now);
        byte[] attributes = cmsBuilder.signedAttributes(prepared.documentDigest(), certificate);
        byte[] signatureValue = sign(attributes);
        if (!cmsBuilder.signatureMatches(attributes, signatureValue, certificate)) {
            throw new PadesSignatureException("SEAL_SIGNATURE_INVALID", "Chữ ký con dấu không khớp chứng thư");
        }
        byte[] cms = cmsBuilder.assemble(attributes, signatureValue, certificate, chain);
        byte[] sealed = embedder.embed(
                prepared.content(), prepared.contentsOffset(), prepared.contentsLength(), cms);
        if (verifier.verify(sealed).size() != 1) {
            throw new PadesSignatureException("SEAL_VERIFY_FAILED", "PDF sau khi đóng dấu không có đúng một chữ ký");
        }
        return sealed;
    }

    private byte[] sign(byte[] attributes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(attributes);
            return signature.sign();
        } catch (GeneralSecurityException exception) {
            throw new PadesSignatureException("SEAL_SIGN_FAILED", "Không ký được con dấu FINORA", exception);
        }
    }

    private static Credential load(TransferDocumentProperties.Seal seal) {
        char[] password = seal.keystorePassword() == null ? new char[0] : seal.keystorePassword().toCharArray();
        try (InputStream input = Files.newInputStream(Path.of(seal.keystorePath()))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password);
            String alias = seal.keyAlias() != null && !seal.keyAlias().isBlank()
                    ? seal.keyAlias()
                    : Collections.list(keyStore.aliases()).stream().filter(name -> isKey(keyStore, name))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Keystore con dấu không có khóa riêng"));
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, password);
            Certificate[] certificates = keyStore.getCertificateChain(alias);
            if (key == null || certificates == null || certificates.length == 0) {
                throw new IllegalStateException("Keystore con dấu thiếu khóa hoặc chứng thư cho alias " + alias);
            }
            List<X509Certificate> rest = new ArrayList<>();
            for (int index = 1; index < certificates.length; index++) {
                rest.add((X509Certificate) certificates[index]);
            }
            return new Credential(key, (X509Certificate) certificates[0], List.copyOf(rest));
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Không đọc được keystore con dấu giấy xác nhận chuyển nhượng", exception);
        }
    }

    private static boolean isKey(KeyStore keyStore, String alias) {
        try {
            return keyStore.isKeyEntry(alias);
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static Credential demo(Instant now) {
        try {
            SecureRandom random = new SecureRandom();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(DEMO_KEY_SIZE, random);
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.C, "VN")
                    .addRDN(BCStyle.O, "FINORA Demo")
                    .addRDN(BCStyle.OU, "Con dau giay xac nhan chuyen nhuong - khong dung cho production")
                    .addRDN(BCStyle.CN, DEMO_COMMON_NAME)
                    .build();
            BigInteger serial = new BigInteger(120, random).add(BigInteger.ONE);
            // java.util.Date chỉ vì API X.509 của BouncyCastle; thời điểm nghiệp vụ vẫn là Instant.
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, serial, Date.from(now.minus(Duration.ofDays(1))),
                    Date.from(now.plus(Duration.ofDays(3 * 365L))), subject, keyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.getPublic()));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            return new Credential(keyPair.getPrivate(), certificate, List.of());
        } catch (GeneralSecurityException | OperatorCreationException | IOException exception) {
            throw new IllegalStateException("Không thể sinh chứng thư demo cho con dấu FINORA", exception);
        }
    }

    private record Credential(PrivateKey privateKey, X509Certificate certificate, List<X509Certificate> chain) {
    }
}
