package com.finora.loan.service.contract.pades;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.springframework.stereotype.Component;

/**
 * Kiểm toàn bộ chữ ký PAdES trong một tệp, theo thứ tự phiên bản.
 *
 * <p>Mỗi chữ ký phải: có ByteRange bao từ byte 0 đến hết một phiên bản (kết thúc bằng {@code %%EOF}), trừ đúng chỗ
 * /Contents; CMS khớp digest và khóa công khai; có content-type, message-digest, signing-certificate-v2 trỏ đúng chứng
 * thư, không có signing-time. Chữ ký cuối phải bao đến byte cuối tệp, tức không có thay đổi nào chưa ký. Chữ ký trước
 * được kiểm trên đúng phần đầu tệp của nó, nên nếu bản sau sửa bytes cũ thì chữ ký trước sẽ hỏng và bị phát hiện.</p>
 */
@Component
public class PadesSignatureVerifier {

    private static final byte[] EOF_MARKER = "%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public List<EmbeddedPadesSignature> verify(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("PDF cần kiểm không được rỗng");
        }
        List<RawSignature> raws = readSignatureDictionaries(pdf);
        raws.sort(Comparator.comparingLong(RawSignature::coveredLength));
        List<EmbeddedPadesSignature> verified = new ArrayList<>();
        long previousCovered = 0;
        for (RawSignature raw : raws) {
            long covered = raw.coveredLength();
            if (covered <= previousCovered) {
                throw invalid("Hai chữ ký cùng bao phủ một phiên bản tệp");
            }
            verified.add(verifyOne(pdf, raw, verified.size() + 1));
            previousCovered = covered;
        }
        if (!raws.isEmpty() && previousCovered != pdf.length) {
            throw invalid("Tệp có thay đổi nằm sau chữ ký cuối cùng");
        }
        return verified;
    }

    private List<RawSignature> readSignatureDictionaries(byte[] pdf) {
        try (PDDocument document = PDDocument.load(pdf)) {
            List<RawSignature> raws = new ArrayList<>();
            for (PDSignatureField field : document.getSignatureFields()) {
                PDSignature signature = field.getValue();
                if (signature == null) {
                    continue;
                }
                int[] byteRange = signature.getByteRange();
                if (byteRange.length != 4) {
                    throw invalid("ByteRange phải có đúng bốn số");
                }
                Calendar signDate = signature.getSignDate();
                raws.add(new RawSignature(
                        field.getFullyQualifiedName(), byteRange, signature.getFilter(), signature.getSubFilter(),
                        signDate == null ? null : signDate.toInstant()));
            }
            return raws;
        } catch (IOException exception) {
            throw new PadesSignatureException("PADES_VERIFY_FAILED", "Không đọc được PDF đã ký", exception);
        }
    }

    private EmbeddedPadesSignature verifyOne(byte[] pdf, RawSignature raw, int revision) {
        int[] range = raw.byteRange();
        long covered = raw.coveredLength();
        if (range[0] != 0 || range[1] <= 0 || range[2] <= range[1] + 2 || range[3] < 0 || covered > pdf.length
                || pdf[range[1]] != '<' || pdf[range[2] - 1] != '>') {
            throw invalid("ByteRange không bao đúng một phiên bản tệp trừ /Contents");
        }
        if (!endsWithEofMarker(pdf, (int) covered)) {
            throw invalid("Phần được ký không kết thúc tại ranh giới phiên bản %%EOF");
        }
        if (!COSName.ADOBE_PPKLITE.getName().equals(raw.filter())
                || !PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName().equals(raw.subFilter())) {
            throw invalid("Chữ ký không phải PAdES (Adobe.PPKLite / ETSI.CAdES.detached)");
        }
        byte[] cms = extractCms(pdf, range);
        byte[] signedContent = new byte[range[1] + range[3]];
        System.arraycopy(pdf, 0, signedContent, 0, range[1]);
        System.arraycopy(pdf, range[2], signedContent, range[1], range[3]);
        try {
            CMSSignedData signedData = new CMSSignedData(new CMSProcessableByteArray(signedContent), cms);
            Collection<SignerInformation> signers = signedData.getSignerInfos().getSigners();
            if (signers.size() != 1) {
                throw invalid("CMS phải có đúng một SignerInfo");
            }
            SignerInformation signer = signers.iterator().next();
            Collection<X509CertificateHolder> matches = signedData.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                throw invalid("CMS không chứa chứng thư của người ký");
            }
            X509CertificateHolder holder = matches.iterator().next();
            if (!NISTObjectIdentifiers.id_sha256.getId().equals(signer.getDigestAlgOID())) {
                throw invalid("Chữ ký phải dùng SHA-256");
            }
            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder))) {
                throw invalid("Giá trị chữ ký không khớp chứng thư");
            }
            requirePadesBaselineAttributes(signer.getSignedAttributes(), holder);
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
            return new EmbeddedPadesSignature(
                    revision, raw.fieldName(), range, (int) covered,
                    PadesDigests.hex(sha256Prefix(pdf, (int) covered)), raw.signingTime(), certificate,
                    PadesDigests.sha256Hex(holder.getEncoded()), PadesDigests.sha256Hex(cms));
        } catch (CMSException | OperatorCreationException | CertificateException | IOException exception) {
            throw new PadesSignatureException("PADES_SIGNATURE_INVALID", "Chữ ký PDF không hợp lệ", exception);
        }
    }

    /** PAdES-B-B: content-type id-data, message-digest, signing-certificate-v2 đúng chứng thư, không signing-time. */
    private static void requirePadesBaselineAttributes(AttributeTable attributes, X509CertificateHolder holder)
            throws IOException {
        if (attributes == null) {
            throw invalid("CMS thiếu signed attributes");
        }
        if (attributes.get(CMSAttributes.signingTime) != null) {
            throw invalid("PAdES-B-B không dùng thuộc tính signing-time");
        }
        Attribute contentType = attributes.get(CMSAttributes.contentType);
        if (contentType == null || !CMSObjectIdentifiers.data.equals(
                ASN1ObjectIdentifier.getInstance(contentType.getAttrValues().getObjectAt(0)))) {
            throw invalid("Thuộc tính content-type phải là id-data");
        }
        if (attributes.get(CMSAttributes.messageDigest) == null) {
            throw invalid("CMS thiếu thuộc tính message-digest");
        }
        Attribute certificateAttribute = attributes.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2);
        if (certificateAttribute == null) {
            throw invalid("CMS thiếu thuộc tính signing-certificate-v2");
        }
        ESSCertIDv2[] identifiers = SigningCertificateV2.getInstance(
                certificateAttribute.getAttrValues().getObjectAt(0)).getCerts();
        if (identifiers.length == 0) {
            throw invalid("signing-certificate-v2 không chứa chứng thư");
        }
        ESSCertIDv2 identifier = identifiers[0];
        if (!NISTObjectIdentifiers.id_sha256.equals(identifier.getHashAlgorithm().getAlgorithm())
                || !Arrays.equals(identifier.getCertHash(), PadesDigests.sha256(holder.getEncoded()))) {
            throw invalid("signing-certificate-v2 không khớp chứng thư ký");
        }
        IssuerSerial issuerSerial = identifier.getIssuerSerial();
        if (issuerSerial != null) {
            boolean issuerMatches = Arrays.stream(issuerSerial.getIssuer().getNames())
                    .anyMatch(name -> name.getTagNo() == GeneralName.directoryName
                            && holder.getIssuer().equals(org.bouncycastle.asn1.x500.X500Name.getInstance(
                                    name.getName())));
            if (!issuerMatches || !issuerSerial.getSerial().getValue().equals(holder.getSerialNumber())) {
                throw invalid("IssuerSerial trong signing-certificate-v2 không khớp chứng thư ký");
            }
        }
    }

    /** Hex của /Contents có đệm '0' phía sau; cắt đúng độ dài DER của ContentInfo, phần thừa bắt buộc toàn số 0. */
    private static byte[] extractCms(byte[] pdf, int[] range) {
        String hex = new String(pdf, range[1] + 1, range[2] - range[1] - 2,
                java.nio.charset.StandardCharsets.US_ASCII);
        byte[] padded;
        try {
            padded = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw invalid("/Contents không phải chuỗi hex hợp lệ");
        }
        if (padded.length < 2 || padded[0] != 0x30) {
            throw invalid("/Contents không chứa CMS");
        }
        int lengthByte = padded[1] & 0xFF;
        int header;
        long length;
        if (lengthByte < 0x80) {
            header = 2;
            length = lengthByte;
        } else {
            int count = lengthByte & 0x7F;
            if (count == 0 || count > 4 || padded.length < 2 + count) {
                throw invalid("CMS phải mã hóa DER độ dài xác định");
            }
            header = 2 + count;
            length = 0;
            for (int index = 0; index < count; index++) {
                length = (length << 8) | (padded[2 + index] & 0xFF);
            }
        }
        long total = header + length;
        if (total > padded.length) {
            throw invalid("CMS dài hơn vùng /Contents");
        }
        for (int index = (int) total; index < padded.length; index++) {
            if (padded[index] != 0) {
                throw invalid("Có dữ liệu lạ sau CMS trong /Contents");
            }
        }
        return Arrays.copyOf(padded, (int) total);
    }

    private static boolean endsWithEofMarker(byte[] pdf, int covered) {
        int end = covered;
        while (end > 0 && (pdf[end - 1] == '\n' || pdf[end - 1] == '\r' || pdf[end - 1] == ' ')) {
            end--;
        }
        return end >= EOF_MARKER.length
                && Arrays.equals(pdf, end - EOF_MARKER.length, end, EOF_MARKER, 0, EOF_MARKER.length);
    }

    private static byte[] sha256Prefix(byte[] pdf, int length) {
        return PadesDigests.sha256(Arrays.copyOf(pdf, length));
    }

    private static PadesSignatureException invalid(String message) {
        return new PadesSignatureException("PADES_SIGNATURE_INVALID", message);
    }

    private record RawSignature(
            String fieldName,
            int[] byteRange,
            String filter,
            String subFilter,
            Instant signingTime
    ) {
        long coveredLength() {
            return (long) byteRange[2] + byteRange[3];
        }
    }
}
