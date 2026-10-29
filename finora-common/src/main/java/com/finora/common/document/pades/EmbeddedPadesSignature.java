package com.finora.common.document.pades;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

/**
 * Một chữ ký PAdES đã kiểm hợp lệ trong tệp.
 *
 * @param revision        thứ tự trong chuỗi (1 = chữ ký đầu tiên)
 * @param coveredLength   số byte đầu tệp mà chữ ký bao phủ; chính là độ dài phiên bản tệp lúc ký
 * @param revisionHash    SHA-256 của {@code coveredLength} byte đầu tệp, tức bản đã ký tại thời điểm đó
 */
public record EmbeddedPadesSignature(
        int revision,
        String fieldName,
        int[] byteRange,
        int coveredLength,
        String revisionHash,
        Instant signingTime,
        X509Certificate certificate,
        String certificateSha256,
        String cmsHash
) {
    public EmbeddedPadesSignature {
        byteRange = Arrays.copyOf(byteRange, byteRange.length);
    }

    @Override
    public int[] byteRange() {
        return Arrays.copyOf(byteRange, byteRange.length);
    }

    public String serialHex() {
        return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
    }

    /** Chỉ lấy CN; các thuộc tính định danh khác của subject (ví dụ UID=CCCD) không được hiển thị lại. */
    public String subjectCommonName() {
        return commonName(X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded()));
    }

    public String issuerCommonName() {
        return commonName(X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()));
    }

    private static String commonName(X500Name name) {
        RDN[] values = name.getRDNs(BCStyle.CN);
        if (values.length == 0) {
            return "(không có CN)";
        }
        return IETFUtils.valueToString(values[0].getFirst().getValue());
    }
}
