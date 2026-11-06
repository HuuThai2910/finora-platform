package com.finora.loan.service.core;

import com.finora.loan.domain.core.entity.FineractCommand;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;

import java.util.List;

/**
 * Các bước transaction của đồng bộ Product sang Fineract: chuẩn bị command, nhận lượt thực thi và ghi kết quả; HTTP
 * Fineract chạy ngoài các transaction này.
 */
public interface CoreProductSyncStateService {

    /** Ghi mapping và command trước; HTTP Fineract chỉ được chạy sau khi transaction này commit. */
    ProductSyncPreparation prepare(long productId, long expectedVersion, String actorId);

    /** Cấp attempt dưới DB lock rồi đóng transaction trước khi service gọi HTTP Fineract. */
    ProductSyncExecution startExecution(String commandId);

    /** Ghi resource ID Fineract, mapping và Product SYNCED trong cùng transaction. */
    void complete(String commandId, FineractProductCreationResult result);

    /** Lỗi tạm thời được xếp lịch retry; lỗi vĩnh viễn mở lại quyền sửa/sync Product. */
    void fail(String commandId, FineractIntegrationException failure);

    /** Command theo ID, không có thì 404. */
    FineractCommand getCommand(String commandId);

    /** Product theo ID, không có thì 404. */
    LoanProduct getProduct(long productId);

    /** Worker chỉ lấy ID của một batch đến hạn, không giữ entity/transaction khi gọi HTTP. */
    List<String> findDueCommandIds(int limit);
}
