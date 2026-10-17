package com.finora.loan.integration.signature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Chứng thư của người ký và chuỗi CA đi kèm (nếu provider trả về).
 *
 * <p>PAdES-B-B bắt buộc nhúng chứng thư ký vào CMS và băm chứng thư đó vào thuộc tính signing-certificate-v2, nên
 * chứng thư phải có trước khi chuẩn bị bản PDF. Chứng thư cá nhân có thể chứa định danh (ví dụ UID=CCCD trong subject):
 * không log, không đưa vào event; chỉ lưu như một phần bằng chứng chữ ký.</p>
 */
public record SigningCertificate(X509Certificate certificate, List<X509Certificate> chain) {

    public SigningCertificate {
        Objects.requireNonNull(certificate, "certificate");
        chain = chain == null ? List.of() : List.copyOf(chain);
    }

    /** Serial dạng hex chữ thường, khớp cách VNPT trả {@code serial_number}. */
    public String serialHex() {
        return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
    }

    public byte[] encodedCertificate() {
        try {
            return certificate.getEncoded();
        } catch (CertificateEncodingException exception) {
            throw new IllegalStateException("Không thể mã hóa DER chứng thư ký", exception);
        }
    }

    /** Ghép DER của từng chứng thư CA; {@link CertificateFactory} đọc lại được chuỗi DER nối tiếp. */
    public byte[] encodedChain() {
        if (chain.isEmpty()) {
            return new byte[0];
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (X509Certificate item : chain) {
                output.write(item.getEncoded());
            }
            return output.toByteArray();
        } catch (CertificateEncodingException exception) {
            throw new IllegalStateException("Không thể mã hóa DER chuỗi chứng thư", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không thể ghép chuỗi chứng thư", exception);
        }
    }

    /** Dựng lại từ bytes đã lưu ở bước chuẩn bị để bước hoàn tất dùng đúng chứng thư đã băm vào signed attributes. */
    public static SigningCertificate fromEncoded(byte[] certificate, byte[] chain) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate signer = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(Objects.requireNonNull(certificate, "certificate")));
            List<X509Certificate> parsedChain = new ArrayList<>();
            if (chain != null && chain.length > 0) {
                for (Certificate item : factory.generateCertificates(new ByteArrayInputStream(chain))) {
                    parsedChain.add((X509Certificate) item);
                }
            }
            return new SigningCertificate(signer, parsedChain);
        } catch (CertificateException exception) {
            throw new IllegalStateException("Chứng thư đã lưu không còn đọc được", exception);
        }
    }
}
