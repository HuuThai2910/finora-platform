package com.finora.loan.service;

import com.finora.loan.dto.request.RepaymentPreviewRequest;
import com.finora.loan.dto.response.RepaymentPreviewResponse;

public interface RepaymentPreviewService {

    RepaymentPreviewResponse preview(long productId, RepaymentPreviewRequest request);
}
