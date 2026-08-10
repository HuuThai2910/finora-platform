package com.finora.loan.service;

import com.finora.loan.dto.request.CreateLoanProductRequest;
import com.finora.loan.dto.request.UpdateLoanProductRequest;
import com.finora.loan.dto.response.LoanProductCatalogResponse;
import com.finora.loan.dto.response.LoanProductResponse;
import com.finora.loan.dto.response.PageResponse;

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

    LoanProductCatalogResponse getActive(long id);

    PageResponse<LoanProductCatalogResponse> listActive(int page, int size);
}
