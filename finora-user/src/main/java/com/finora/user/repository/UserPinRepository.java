package com.finora.user.repository;

import com.finora.user.domain.UserPin;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository truy vấn bảng {@code user_pins}.
 */
public interface UserPinRepository extends JpaRepository<UserPin, UUID> {

    /**
     * Khoá dòng khi kiểm PIN: nhiều lần nhập sai gửi đồng thời phải được đếm đủ,
     * nếu không kẻ dò PIN vượt được giới hạn 5 lần bằng cách bắn song song.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPin p where p.keycloakUserId = :keycloakUserId")
    Optional<UserPin> findForUpdate(@Param("keycloakUserId") UUID keycloakUserId);
}
