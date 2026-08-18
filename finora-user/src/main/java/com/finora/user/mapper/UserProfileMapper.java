package com.finora.user.mapper;

import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.UserProfileResponse;
import org.mapstruct.*;

/**
 * MapStruct mapper chuyển đổi giữa entity {@link UserProfile} và DTO.
 * <p>
 * CryptoConverter tự động giải mã khi JPA đọc, nên {@code idNumberEncrypted}
 * và {@code phoneEncrypted} trong entity đã là plaintext tại thời điểm mapping.
 */
@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
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

    /**
     * Cập nhật entity từ request — chỉ ghi đè các trường khác null (partial update).
     * Các trường không có trong request (id, keycloakUserId, hash, encrypted, role...)
     * được giữ nguyên, không phải lỗi unmapped.
     */
    @BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)
    void updateFromRequest(UpdateProfileRequest request, @MappingTarget UserProfile entity);
}
