package com.finora.user.repository;

import com.finora.user.domain.EkycEvidenceAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ghi bảng {@code ekyc_evidence_access_log}; chỉ thêm, không sửa/xoá. */
public interface EkycEvidenceAccessLogRepository extends JpaRepository<EkycEvidenceAccessLog, Long> {
}
