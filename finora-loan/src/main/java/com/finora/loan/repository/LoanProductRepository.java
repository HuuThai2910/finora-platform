package com.finora.loan.repository;

import com.finora.loan.domain.LoanProduct;
import com.finora.loan.domain.LoanProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

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
     * Khóa Product khi submit hồ sơ hoặc chuẩn bị sync để snapshot không xen kẽ
     * với thao tác deactivate/update hay một sync command cạnh tranh.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from LoanProduct product where product.id = :id")
    Optional<LoanProduct> findByIdForSubmit(@Param("id") Long id);
}
