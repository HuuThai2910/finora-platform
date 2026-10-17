package com.finora.loan.integration.signature;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Khóa và chứng thư tự ký của chế độ MOCK (D13): sinh mới mỗi lần khởi động, chỉ nằm trong bộ nhớ, không ghi ra đĩa,
 * không có keystore. Subject ghi rõ "không có giá trị pháp lý" để trình đọc PDF hiển thị đúng bản chất chữ ký demo.
 *
 * <p>Chữ ký cũ vẫn kiểm được sau khi khởi động lại vì chứng thư được nhúng trong CMS; chỉ khóa riêng bị bỏ.</p>
 */
final class DemoSigningCredential {

    static final String SUBJECT_COMMON_NAME = "FINORA DEMO - KHONG CO GIA TRI PHAP LY";
    private static final int KEY_SIZE = 2048;

    private final PrivateKey privateKey;
    private final SigningCertificate signingCertificate;

    private DemoSigningCredential(PrivateKey privateKey, SigningCertificate signingCertificate) {
        this.privateKey = privateKey;
        this.signingCertificate = signingCertificate;
    }

    static DemoSigningCredential generate(Instant now) {
        try {
            SecureRandom random = new SecureRandom();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, random);
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.C, "VN")
                    .addRDN(BCStyle.O, "FINORA Demo")
                    .addRDN(BCStyle.OU, "MOCK signer - khong dung cho production")
                    .addRDN(BCStyle.CN, SUBJECT_COMMON_NAME)
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
            return new DemoSigningCredential(keyPair.getPrivate(), new SigningCertificate(certificate, List.of()));
        } catch (GeneralSecurityException | OperatorCreationException | IOException exception) {
            throw new IllegalStateException("Không thể sinh chứng thư demo cho chữ ký MOCK", exception);
        }
    }

    SigningCertificate signingCertificate() {
        return signingCertificate;
    }

    /**
     * Ký digest SHA-256 theo đúng cách một dịch vụ "ký hash" từ xa làm: RSA PKCS#1 v1.5 trên DigestInfo(SHA-256,
     * digest). Kết quả bằng SHA256withRSA trên dữ liệu gốc nên CMS kiểm được bằng thư viện chuẩn.
     */
    byte[] signDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("Digest phải là SHA-256 32 byte");
        }
        try {
            byte[] digestInfo = new DigestInfo(
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE), digest)
                    .getEncoded(ASN1Encoding.DER);
            Signature signature = Signature.getInstance("NONEwithRSA");
            signature.initSign(privateKey);
            signature.update(digestInfo);
            return signature.sign();
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Không thể ký digest bằng khóa demo", exception);
        }
    }
}
