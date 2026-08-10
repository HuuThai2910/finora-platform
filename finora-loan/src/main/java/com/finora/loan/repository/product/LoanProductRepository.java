package com.finora.loan.repository.product;

import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.domain.product.LoanProductStatus;
import com.finora.loan.domain.product.CoreSyncStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Truy cập dữ liệu Loan Product trong database riêng của Loan Service.
 *
 * <p>Không cần khai báo {@code @Repository}: Spring Data JPA tự phát hiện interface
 * kế thừa {@link JpaRepository}, tạo proxy implementation và đăng ký Spring Bean.</p>
 */
public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    boolean existsByCode(String code);

    Optional<LoanProduct> findByCode(String code);

    Page<LoanProduct> findByStatus(LoanProductStatus status, Pageable pageable);

    /**
     * Danh sách quản trị cho phép bỏ trống từng bộ lọc. Product chỉ có scalar/current mapping ID,
     * vì vậy mapping sang DTO không kích hoạt thêm query theo từng dòng.
     */
    @Query("""
            select product
            from LoanProduct product
            where (:status is null or product.status = :status)
              and (:coreSyncStatus is null or product.coreSyncStatus = :coreSyncStatus)
            """)
    Page<LoanProduct> findAdminProducts(
            @Param("status") LoanProductStatus status,
            @Param("coreSyncStatus") CoreSyncStatus coreSyncStatus,
            Pageable pageable
    );

    /**
     * Khóa Product khi submit hồ sơ hoặc chuẩn bị sync để snapshot không xen kẽ
     * với thao tác deactivate/update hay một sync command cạnh tranh.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from LoanProduct product where product.id = :id")
    Optional<LoanProduct> findByIdForSubmit(@Param("id") Long id);
}
