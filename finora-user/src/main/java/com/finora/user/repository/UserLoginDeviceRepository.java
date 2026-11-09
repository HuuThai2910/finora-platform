package com.finora.user.repository;

import com.finora.user.domain.entity.UserLoginDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserLoginDeviceRepository extends JpaRepository<UserLoginDevice, Long> {

    Optional<UserLoginDevice> findByUserProfileIdAndDeviceId(Long userProfileId, String deviceId);

    /** Thiết bị của một tài khoản, đăng nhập gần nhất trước. */
    List<UserLoginDevice> findByUserProfileIdOrderByLastLoginAtDescIdDesc(Long userProfileId);

    Optional<UserLoginDevice> findByIdAndUserProfileId(Long id, Long userProfileId);
}
