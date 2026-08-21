package com.finora.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Kiểm tra luật đối chiếu dữ liệu OCR với hồ sơ người dùng. */
class CccdMatcherTest {

    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);

    @Nested
    @DisplayName("Chuẩn hoá họ tên")
    class NormalizeName {

        @Test
        void boDauVaVietHoa() {
            assertThat(CccdMatcher.normalizeName("Nguyễn Văn A")).isEqualTo("NGUYEN VAN A");
        }

        @Test
        void chuDDuocXuLyRieng() {
            // Đ không tách được dấu bằng chuẩn hoá Unicode NFD
            assertThat(CccdMatcher.normalizeName("Đặng Đình Đức")).isEqualTo("DANG DINH DUC");
        }

        @Test
        void gopKhoangTrangThua() {
            assertThat(CccdMatcher.normalizeName("  NGUYEN   VAN  A ")).isEqualTo("NGUYEN VAN A");
        }

        @Test
        void giaTriNullTraChuoiRong() {
            assertThat(CccdMatcher.normalizeName(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Đọc ngày sinh")
    class ParseDate {

        @Test
        void dinhDangDauCheo() {
            assertThat(CccdMatcher.parseDate("01/01/2000")).contains(DOB);
        }

        @Test
        void dinhDangDauGach() {
            assertThat(CccdMatcher.parseDate("01-01-2000")).contains(DOB);
        }

        @Test
        void chuoiKhongPhaiNgayThiRong() {
            assertThat(CccdMatcher.parseDate("khong-doc-duoc")).isEmpty();
        }

        @Test
        void ngayKhongTonTaiThiRong() {
            assertThat(CccdMatcher.parseDate("31/02/2000")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cảnh báo trường mềm")
    class SoftFieldWarnings {

        @Test
        void trungKhopThiKhongCoCanhBao() {
            assertThat(CccdMatcher.softFieldWarnings(
                    "Nguyễn Văn A", DOB, "NGUYEN VAN A", "01/01/2000")).isEmpty();
        }

        @Test
        void hoTenLechThiCanhBao() {
            assertThat(CccdMatcher.softFieldWarnings(
                    "Nguyễn Văn A", DOB, "TRAN VAN B", "01/01/2000"))
                    .containsExactly(CccdMatcher.WARNING_FULL_NAME_MISMATCH);
        }

        @Test
        void ngaySinhLechThiCanhBao() {
            assertThat(CccdMatcher.softFieldWarnings(
                    "Nguyễn Văn A", DOB, "NGUYEN VAN A", "02/01/2000"))
                    .containsExactly(CccdMatcher.WARNING_DOB_MISMATCH);
        }

        @Test
        void ocrKhongDocDuocThiKhongCanhBao() {
            // "Không đọc được" không phải bằng chứng của "sai"
            assertThat(CccdMatcher.softFieldWarnings("Nguyễn Văn A", DOB, null, null)).isEmpty();
        }

        @Test
        void caHaiTruongLechThiCanhBaoCaHai() {
            assertThat(CccdMatcher.softFieldWarnings(
                    "Nguyễn Văn A", DOB, "TRAN VAN B", "02/01/2000"))
                    .containsExactlyInAnyOrder(
                            CccdMatcher.WARNING_FULL_NAME_MISMATCH,
                            CccdMatcher.WARNING_DOB_MISMATCH);
        }
    }
}
