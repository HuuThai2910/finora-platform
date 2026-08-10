package com.finora.loan.service.core;

import com.finora.loan.dto.core.request.RepaymentPreviewRequest;
import com.finora.loan.dto.core.response.RepaymentPreviewResponse;

public interface RepaymentPreviewService {

    RepaymentPreviewResponse preview(long productId, RepaymentPreviewRequest request);
}
