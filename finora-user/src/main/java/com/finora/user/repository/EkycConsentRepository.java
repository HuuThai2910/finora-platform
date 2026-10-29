package com.finora.user.repository;

import com.finora.user.domain.EkycConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Truy vấn bảng {@code ekyc_consents}. */
public interface EkycConsentRepository extends JpaRepository<EkycConsent, Long> {

    /**
     * Bản ghi đồng ý mới nhất chưa rút của người dùng; index {@code (user_profile_id, accepted_at DESC)}
     * phục vụ đúng câu này nên mỗi bước eKYC chỉ đọc một dòng.
     */
    Optional<EkycConsent> findFirstByUserProfileIdAndWithdrawnAtIsNullOrderByAcceptedAtDescIdDesc(Long userProfileId);

    /** Mọi bản ghi còn hiệu lực để đánh dấu rút lại cùng lúc. */
    List<EkycConsent> findByUserProfileIdAndWithdrawnAtIsNull(Long userProfileId);
}
