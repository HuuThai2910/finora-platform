package com.finora.notification.repository;

import com.finora.notification.domain.PushDevice;
import com.finora.notification.domain.PushDeviceStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    /** Đăng ký lại cùng token (mở app, đổi tài khoản trên một máy) phải tuần tự với nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDevice d where d.token = :token")
    Optional<PushDevice> findByTokenForUpdate(@Param("token") String token);

    /** Intake fan-out: dùng index {@code (user_id, status, last_registered_at)}. */
    List<PushDevice> findByUserIdInAndStatus(Collection<String> userIds, PushDeviceStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from PushDevice d
            where d.userId = :userId and d.status = :status
            order by d.lastRegisteredAt desc, d.id
            """)
    List<PushDevice> findByUserForUpdate(@Param("userId") String userId, @Param("status") PushDeviceStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDevice d where d.id = :id")
    Optional<PushDevice> findByIdForUpdate(@Param("id") UUID id);
}
