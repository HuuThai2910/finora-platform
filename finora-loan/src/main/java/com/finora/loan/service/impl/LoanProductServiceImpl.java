package com.finora.loan.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.LoanProduct;
import com.finora.loan.domain.LoanProductStatus;
import com.finora.loan.dto.request.CreateLoanProductRequest;
import com.finora.loan.dto.request.UpdateLoanProductRequest;
import com.finora.loan.dto.response.LoanProductCatalogResponse;
import com.finora.loan.dto.response.LoanProductResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.LoanProductMapper;
import com.finora.loan.repository.LoanProductRepository;
import com.finora.loan.service.LoanProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanProductServiceImpl implements LoanProductService {

    private final LoanProductRepository repository;
    private final LoanProductMapper mapper;
    private final MockCurrentUserProvider currentUser;
    private final Clock clock;

    /** Tạo Product DRAFT/NOT_SYNCED; unique constraint vẫn là hàng rào cuối khi request cạnh tranh. */
    @Override
    @Transactional
    public LoanProductResponse create(CreateLoanProductRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        if (repository.existsByCode(normalizedCode)) {
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
                request.annualInterestRate(),
                request.repaymentMethod(),
                currentUser.adminUserId(),
                Instant.now(clock)
        );
        try {
            repository.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_CODE_EXISTS", "Mã sản phẩm đã tồn tại");
        }
        log.info("Đã tạo Loan Product: productId={}, code={}, actorId={}",
                product.getId(), product.getCode(), currentUser.adminUserId());
        return mapper.toResponse(product);
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
                request.annualInterestRate(),
                request.repaymentMethod(),
                request.version(),
                currentUser.adminUserId(),
                Instant.now(clock)
        );
        repository.saveAndFlush(product);
        log.info("Đã cập nhật Loan Product: productId={}, configurationVersion={}, actorId={}",
                id, product.getConfigurationVersion(), currentUser.adminUserId());
        return mapper.toResponse(product);
    }

    @Override
    @Transactional
    public LoanProductResponse activate(long id, long version) {
        return transition(id, product -> product.activate(version, actor(), now()), "ACTIVE");
    }

    @Override
    @Transactional
    public LoanProductResponse deactivate(long id, long version) {
        return transition(id, product -> product.deactivate(version, actor(), now()), "INACTIVE");
    }

    @Override
    @Transactional
    public LoanProductResponse archive(long id, long version) {
        return transition(id, product -> product.archive(version, actor(), now()), "ARCHIVED");
    }

    @Override
    @Transactional(readOnly = true)
    public LoanProductResponse getAdminDetail(long id) {
        return mapper.toResponse(getProduct(id));
    }

    @Override
    @Transactional(readOnly = true)
    public LoanProductCatalogResponse getActive(long id) {
        LoanProduct product = getProduct(id);
        product.requireAvailable();
        return mapper.toCatalogResponse(product);
    }

    /** List chỉ tải scalar Product, có pagination và không fetch mapping collection nên không phát sinh N+1. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanProductCatalogResponse> listActive(int page, int size) {
        Page<LoanProductCatalogResponse> products = repository.findByStatus(
                        LoanProductStatus.ACTIVE,
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                )
                .map(mapper::toCatalogResponse);
        return PageResponse.from(products);
    }

    private LoanProductResponse transition(long id, Consumer<LoanProduct> action, String target) {
        LoanProduct product = getProduct(id);
        // Domain method chịu trách nhiệm whitelist transition và kiểm tra optimistic version trước khi đổi state.
        action.accept(product);
        repository.saveAndFlush(product);
        log.info("Đã chuyển trạng thái Loan Product: productId={}, targetStatus={}, actorId={}", id, target, actor());
        return mapper.toResponse(product);
    }

    private LoanProduct getProduct(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", id));
    }

    private String actor() {
        return currentUser.adminUserId();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
