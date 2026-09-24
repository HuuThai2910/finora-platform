package com.finora.investment.service;

import com.finora.investment.dto.response.CommitmentResponse;
import com.finora.investment.dto.response.PortfolioPositionResponse;
import com.finora.investment.dto.response.PortfolioResponse;
import java.util.List;

/**
 * Danh mục đầu tư của nhà đầu tư đang đăng nhập.
 *
 * <p>Tách interface khỏi cài đặt theo đúng quy ước của finora-user và finora-loan:
 * controller phụ thuộc vào hợp đồng này, không phụ thuộc vào lớp cài đặt.</p>
 */
public interface PortfolioService {

    PortfolioResponse portfolio();

    /** Các phần vốn đã cam kết của nhà đầu tư hiện tại, kể cả khoản vay chưa giải ngân. */
    List<CommitmentResponse> myCommitments();
}
