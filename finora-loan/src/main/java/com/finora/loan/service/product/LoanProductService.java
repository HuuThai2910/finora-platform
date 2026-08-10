package com.finora.loan.service.product;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.product.request.CreateLoanProductRequest;
import com.finora.loan.dto.product.request.UpdateLoanProductRequest;
import com.finora.loan.dto.product.response.LoanProductCatalogResponse;
import com.finora.loan.dto.product.response.LoanProductResponse;
import com.finora.loan.domain.product.CoreSyncStatus;
import com.finora.loan.domain.product.LoanProductStatus;

/**
 * Hợp đồng nghiệp vụ nội bộ cho chức năng quản lý và tra cứu sản phẩm vay.
 *
 * <p>Controller chỉ phụ thuộc interface này. Luồng xử lý, transaction và giải thích
 * chi tiết được đặt tại implementation để người đọc theo dõi code ở cùng một nơi.</p>
 */
public interface LoanProductService {

    LoanProductResponse create(CreateLoanProductRequest request);

    LoanProductResponse update(long id, UpdateLoanProductRequest request);

    LoanProductResponse activate(long id, long version);

    LoanProductResponse deactivate(long id, long version);

    LoanProductResponse archive(long id, long version);

    LoanProductResponse getAdminDetail(long id);

    PageResponse<LoanProductResponse> listAdmin(
            LoanProductStatus status,
            CoreSyncStatus coreSyncStatus,
            int page,
            int size
    );

    LoanProductCatalogResponse getActive(long id);

    PageResponse<LoanProductCatalogResponse> listActive(int page, int size);
}
