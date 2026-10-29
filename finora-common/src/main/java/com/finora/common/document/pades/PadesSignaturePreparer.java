package com.finora.common.document.pades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.GregorianCalendar;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;

/**
 * Thêm một trường chữ ký ẩn vào PDF bằng incremental update của PDFBox và chừa trống /Contents.
 *
 * <p>Incremental update chỉ nối thêm bytes vào cuối tệp: mọi byte của bản trước giữ nguyên, nên chữ ký đã có vẫn
 * khớp phần nó bao phủ. Bước này không ký; nó trả digest của ByteRange để dựng signed attributes, còn giá trị chữ ký
 * được nhúng sau vào đúng chỗ trống (provider từ xa có thể mất vài phút để người dùng xác nhận).</p>
 */
public class PadesSignaturePreparer {

    /** 16 KiB cho CMS: chứng thư ký + CA + root khoảng 5 KiB, còn chỗ cho dấu thời gian nếu nâng lên PAdES-B-T. */
    public static final int RESERVED_SIGNATURE_BYTES = 16_384;
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    public PreparedPadesRevision prepare(byte[] basePdf, PadesSignatureLabel label, Instant signingTime) {
        if (basePdf == null || basePdf.length == 0) {
            throw new IllegalArgumentException("PDF gốc không được rỗng");
        }
        // Khóa /M chỉ có độ phân giải giây; lưu cùng giá trị đã cắt để DB và PDF không lệch nhau.
        Instant signedAt = signingTime.truncatedTo(ChronoUnit.SECONDS);
        try (PDDocument document = PDDocument.load(basePdf); SignatureOptions options = new SignatureOptions()) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(label.name());
            signature.setReason(label.reason());
            signature.setLocation(label.location());
            signature.setSignDate(GregorianCalendar.from(signedAt.atZone(VIETNAM)));
            options.setPreferredSignatureSize(RESERVED_SIGNATURE_BYTES);
            // Trường ẩn gắn vào trang cuối (trang xác nhận các bên); chữ ký ẩn nên không đổi nội dung hiển thị.
            options.setPage(document.getNumberOfPages() - 1);
            document.addSignature(signature, options);

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    basePdf.length + 2 * RESERVED_SIGNATURE_BYTES + 16_384);
            ExternalSigningSupport external = document.saveIncrementalForExternalSigning(output);
            byte[] digest;
            try (InputStream content = external.getContent()) {
                digest = PadesDigests.sha256(content);
            }
            // Mảng rỗng giữ nguyên chỗ trống '0'; CMS thật được nhúng sau bằng PadesSignatureEmbedder.
            external.setSignature(new byte[0]);
            byte[] prepared = output.toByteArray();
            int[] byteRange = signature.getByteRange();
            requireValidPlaceholder(basePdf, prepared, byteRange, digest);
            return new PreparedPadesRevision(
                    prepared, PadesDigests.sha256Hex(prepared), byteRange[1], byteRange[2] - byteRange[1],
                    digest, signedAt);
        } catch (IOException exception) {
            throw new PadesSignatureException(
                    "PADES_PREPARE_FAILED", "Không thể chuẩn bị chữ ký PDF", exception);
        }
    }

    /**
     * Fail closed nếu PDFBox không tạo đúng cấu trúc mong đợi: bản gốc phải là tiền tố nguyên vẹn, chỗ trống nằm trong
     * phần nối thêm và ByteRange bao toàn bộ tệp trừ đúng chỗ trống đó.
     */
    private static void requireValidPlaceholder(byte[] base, byte[] prepared, int[] byteRange, byte[] digest) {
        if (byteRange.length != 4 || byteRange[0] != 0 || byteRange[1] < base.length
                || byteRange[2] <= byteRange[1] + 2
                || (long) byteRange[2] + byteRange[3] != prepared.length
                || prepared[byteRange[1]] != '<' || prepared[byteRange[2] - 1] != '>') {
            throw new PadesSignatureException(
                    "PADES_PLACEHOLDER_INVALID", "ByteRange của chữ ký PDF không đúng cấu trúc");
        }
        if (!Arrays.equals(prepared, 0, base.length, base, 0, base.length)) {
            throw new PadesSignatureException(
                    "PADES_BASE_MODIFIED", "Incremental update đã thay đổi bytes của bản PDF trước");
        }
        for (int index = byteRange[1] + 1; index < byteRange[2] - 1; index++) {
            if (prepared[index] != '0') {
                throw new PadesSignatureException(
                        "PADES_PLACEHOLDER_INVALID", "Chỗ trống /Contents không còn rỗng");
            }
        }
        if (!Arrays.equals(digest, PadesDigests.sha256(prepared, byteRange))) {
            throw new PadesSignatureException(
                    "PADES_DIGEST_MISMATCH", "Digest PDFBox không khớp ByteRange của bản đã chuẩn bị");
        }
    }
}
