package com.finora.user.mapper;

import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper chuyển đổi entity {@link UserProfile} sang DTO.
 * <p>
 * CryptoConverter tự động giải mã khi JPA đọc, nên {@code idNumberEncrypted}
 * và {@code phoneEncrypted} trong entity đã là plaintext tại thời điểm mapping.
 */
@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    /**
     * Chuyển entity sang response DTO.
     * <p>
     * Map trường {@code idNumberEncrypted} → {@code idNumber}
     * và {@code phoneEncrypted} → {@code phone}.
     */
    @Mapping(source = "idNumberEncrypted", target = "idNumber")
    @Mapping(source = "phoneEncrypted", target = "phone")
    UserProfileResponse toResponse(UserProfile entity);
}
