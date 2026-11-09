package com.finora.loan.service.product.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.domain.product.enums.CoreSyncStatus;
import com.finora.loan.domain.product.enums.LoanProductStatus;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.dto.product.request.CreateLoanProductRequest;
import com.finora.loan.dto.product.request.UpdateLoanProductRequest;
import com.finora.loan.dto.product.response.LoanProductCatalogResponse;
import com.finora.loan.dto.product.response.LoanProductResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.product.LoanProductMapper;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.service.product.LoanProductService;
import com.finora.loan.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Quản lý và tra cứu sản phẩm vay (Loan Product).
 * <p>
 * Thao tác ghi kiểm quyền Thao tác "Sản phẩm vay" và version để hai thao tác admin không ghi đè nhau; danh sách chỉ đọc
 * scalar của Product nên không phát sinh N+1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanProductServiceImpl implements LoanProductService {

    private final LoanProductRepository loanProductRepository;
    private final LoanProductMapper loanProductMapper;
    private final Clock clock;

    /** Tạo Product DRAFT/NOT_SYNCED; unique constraint vẫn là hàng rào cuối khi request cạnh tranh. */
    @Override
    @Transactional
    public LoanProductResponse create(CreateLoanProductRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        if (loanProductRepository.existsByCode(normalizedCode)) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_CODE_EXISTS", "Mã sản phẩm đã tồn tại");
        }
        LoanProduct product = LoanProduct.create(
                normalizedCode,
                request.name(),
                request.description(),
                request.minAmount(),
                request.maxAmount(),
                request.minTermMonths(),
                request.maxTermMonths(),
                rateOrBase(request.minAnnualInterestRate(), request.annualInterestRate()),
                request.annualInterestRate(),
                rateOrBase(request.maxAnnualInterestRate(), request.annualInterestRate()),
                request.repaymentMethod(),
                actor(),
                Instant.now(clock)
        );
        try {
            loanProductRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_CODE_EXISTS", "Mã sản phẩm đã tồn tại");
        }
        log.info("Đã tạo Loan Product: productId={}, code={}, actorId={}",
                product.getId(), product.getCode(), actor());
        return loanProductMapper.toResponse(product);
    }

    /** Sửa điều khoản chỉ khi DRAFT; domain tự reset core sync để bắt buộc đồng bộ cấu hình mới. */
    @Override
    @Transactional
    public LoanProductResponse update(long id, UpdateLoanProductRequest request) {
        LoanProduct product = getProduct(id);
        product.updateConfiguration(
                request.name(),
                request.description(),
                request.minAmount(),
                request.maxAmount(),
                request.minTermMonths(),
                request.maxTermMonths(),
                rateOrBase(request.minAnnualInterestRate(), request.annualInterestRate()),
                request.annualInterestRate(),
                rateOrBase(request.maxAnnualInterestRate(), request.annualInterestRate()),
                request.repaymentMethod(),
                request.version(),
                actor(),
                Instant.now(clock)
        );
        loanProductRepository.saveAndFlush(product);
        log.info("Đã cập nhật Loan Product: productId={}, configurationVersion={}, actorId={}",
                id, product.getConfigurationVersion(), actor());
        return loanProductMapper.toResponse(product);
    }

    /** Kích hoạt chỉ đổi state local sau khi domain xác nhận mapping Fineract hiện hành đã SYNCED. */
    @Override
    @Transactional
    public LoanProductResponse activate(long id, long version) {
        return transition(id, product -> product.activate(version, actor(), now()), "ACTIVE");
    }

    /** Ngừng nhận hồ sơ mới nhưng giữ nguyên Product snapshot của các hồ sơ đã nộp. */
    @Override
    @Transactional
    public LoanProductResponse deactivate(long id, long version) {
        return transition(id, product -> product.deactivate(version, actor(), now()), "INACTIVE");
    }

    /** Lưu trữ Product có kiểm tra version để hai thao tác admin không ghi đè nhau. */
    @Override
    @Transactional
    public LoanProductResponse archive(long id, long version) {
        return transition(id, product -> product.archive(version, actor(), now()), "ARCHIVED");
    }

    @Override
    @Transactional(readOnly = true)
    public LoanProductResponse getAdminDetail(long id) {
        return loanProductMapper.toResponse(getProduct(id));
    }

    /**
     * Trả cả Product chưa công bố để admin tiếp tục sync/sửa thay vì chỉ thấy catalog ACTIVE.
     * Query vẫn phân trang và chỉ đọc scalar của Product nên số câu SQL không tăng theo kích thước trang.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanProductResponse> listAdmin(
            LoanProductStatus status,
            CoreSyncStatus coreSyncStatus,
            String q,
            int page,
            int size
    ) {
        Page<LoanProductResponse> products = loanProductRepository.findAdminProducts(
                        status,
                        coreSyncStatus,
                        SearchPatterns.containsLower(q),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                )
                .map(loanProductMapper::toResponse);
        return PageResponse.from(products);
    }

    /** Borrower chỉ đọc Product ACTIVE có core mapping sẵn sàng. */
    @Override
    @Transactional(readOnly = true)
    public LoanProductCatalogResponse getActive(long id) {
        LoanProduct product = getProduct(id);
        product.requireAvailable();
        return loanProductMapper.toCatalogResponse(product);
    }

    /** List chỉ tải scalar Product, có pagination và không fetch mapping collection nên không phát sinh N+1. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanProductCatalogResponse> listActive(int page, int size) {
        Page<LoanProductCatalogResponse> products = loanProductRepository.findByStatus(
                        LoanProductStatus.ACTIVE,
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                )
                .map(loanProductMapper::toCatalogResponse);
        return PageResponse.from(products);
    }

    private LoanProductResponse transition(long id, Consumer<LoanProduct> action, String target) {
        LoanProduct product = getProduct(id);
        // Domain method chịu trách nhiệm whitelist transition và kiểm tra optimistic version trước khi đổi state.
        action.accept(product);
        loanProductRepository.saveAndFlush(product);
        log.info("Đã chuyển trạng thái Loan Product: productId={}, targetStatus={}, actorId={}", id, target, actor());
        return loanProductMapper.toResponse(product);
    }

    private LoanProduct getProduct(long id) {
        return loanProductRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", id));
    }

    /** Người thao tác Product; chỉ ai có quyền Thao tác "Sản phẩm vay" (hoặc ADMIN) mới đổi được Product. */
    private String actor() {
        SecurityUtils.requirePermission(OpsFunction.LOAN_PRODUCT.action());
        return SecurityUtils.getCurrentUserId();
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** Request cũ chỉ có annualInterestRate tiếp tục tạo Product fixed-rate (min = base = max). */
    private BigDecimal rateOrBase(BigDecimal configuredRate, BigDecimal baseRate) {
        return configuredRate == null ? baseRate : configuredRate;
    }
}
