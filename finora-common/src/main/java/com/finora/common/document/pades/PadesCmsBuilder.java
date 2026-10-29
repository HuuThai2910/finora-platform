package com.finora.common.document.pades;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerIdentifier;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;

/**
 * Dựng CMS SignedData tách rời (detached) theo PAdES-B-B (ETSI EN 319 142-1) cho chữ ký do provider ngoài tạo.
 *
 * <p>Ký hai bước nên signed attributes phải tất định: content-type = id-data, message-digest = SHA-256 của ByteRange
 * và signing-certificate-v2 gắn đúng chứng thư ký. Không có signing-time vì PAdES-B-B lấy thời điểm ký từ khóa /M của
 * từ điển chữ ký. Provider chỉ nhận SHA-256 của DER signed attributes; giá trị chữ ký RSA PKCS#1 v1.5 trả về được kiểm
 * bằng khóa công khai của chứng thư trước khi đóng gói, nên chữ ký sai không bao giờ được nhúng vào PDF.</p>
 */
public class PadesCmsBuilder {

    private static final AlgorithmIdentifier SHA256 = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
    private static final AlgorithmIdentifier RSA_ENCRYPTION =
            new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);

    /** DER của SET OF Attribute; chính bytes này được băm để gửi ký và được đặt nguyên vào SignerInfo. */
    public byte[] signedAttributes(byte[] documentDigest, X509Certificate signer) {
        if (documentDigest == null || documentDigest.length != 32) {
            throw new IllegalArgumentException("Digest tài liệu phải là SHA-256 32 byte");
        }
        try {
            ASN1EncodableVector attributes = new ASN1EncodableVector();
            attributes.add(new Attribute(CMSAttributes.contentType, new DERSet(CMSObjectIdentifiers.data)));
            attributes.add(new Attribute(CMSAttributes.messageDigest, new DERSet(new DEROctetString(documentDigest))));
            attributes.add(new Attribute(
                    PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(signingCertificateV2(signer))));
            return new DERSet(attributes).getEncoded(ASN1Encoding.DER);
        } catch (IOException | CertificateEncodingException exception) {
            throw new PadesSignatureException(
                    "PADES_ATTRIBUTES_FAILED", "Không thể dựng signed attributes PAdES", exception);
        }
    }

    public byte[] digest(byte[] signedAttributes) {
        return PadesDigests.sha256(signedAttributes);
    }

    /** Chữ ký hợp lệ khi RSA PKCS#1 v1.5 trên SHA-256(signed attributes) khớp khóa công khai của chứng thư. */
    public boolean signatureMatches(byte[] signedAttributes, byte[] signatureValue, X509Certificate signer) {
        if (signatureValue == null || signatureValue.length == 0) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(signer.getPublicKey());
            verifier.update(signedAttributes);
            return verifier.verify(signatureValue);
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    /** ContentInfo(SignedData) DER: eContent vắng mặt (detached), certificates gồm chứng thư ký và chuỗi CA. */
    public byte[] assemble(
            byte[] signedAttributes,
            byte[] signatureValue,
            X509Certificate signer,
            List<X509Certificate> chain
    ) {
        try {
            Certificate signerCertificate = Certificate.getInstance(signer.getEncoded());
            ASN1Set attributes = ASN1Set.getInstance(ASN1Primitive.fromByteArray(signedAttributes));
            SignerInfo signerInfo = new SignerInfo(
                    new SignerIdentifier(new IssuerAndSerialNumber(signerCertificate)),
                    SHA256,
                    attributes,
                    RSA_ENCRYPTION,
                    new DEROctetString(signatureValue),
                    null);
            ASN1EncodableVector certificates = new ASN1EncodableVector();
            certificates.add(signerCertificate);
            for (X509Certificate certificate : chain) {
                certificates.add(Certificate.getInstance(certificate.getEncoded()));
            }
            SignedData signedData = new SignedData(
                    new DERSet(SHA256),
                    new ContentInfo(CMSObjectIdentifiers.data, null),
                    new DERSet(certificates),
                    null,
                    new DERSet(signerInfo));
            return new ContentInfo(CMSObjectIdentifiers.signedData, signedData).getEncoded(ASN1Encoding.DER);
        } catch (IOException | CertificateEncodingException exception) {
            throw new PadesSignatureException("PADES_CMS_FAILED", "Không thể đóng gói CMS SignedData", exception);
        }
    }

    /** ESSCertIDv2 dùng SHA-256 (giá trị mặc định nên không mã hóa hashAlgorithm) và IssuerSerial của chứng thư. */
    private static SigningCertificateV2 signingCertificateV2(X509Certificate signer)
            throws CertificateEncodingException {
        Certificate certificate = Certificate.getInstance(signer.getEncoded());
        IssuerSerial issuerSerial = new IssuerSerial(
                new GeneralNames(new GeneralName(certificate.getIssuer())),
                certificate.getSerialNumber().getValue());
        return new SigningCertificateV2(new ESSCertIDv2[] {
                new ESSCertIDv2(PadesDigests.sha256(signer.getEncoded()), issuerSerial)
        });
    }
}
