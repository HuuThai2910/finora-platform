package com.finora.loan.service.contract;

/**
 * Đối soát phiên ký SmartCA phía server (B47): người dùng xác nhận trên app SmartCA rồi không quay lại FINORA thì
 * chữ ký vẫn được thu về; phiên bỏ dở quá hạn được đóng để bên ký tiếp theo không bị chặn mãi.
 */
public interface ContractPdfSignatureWorker {

    /** Không có transaction bao lượt quét; mỗi phiên đi qua service riêng với lời gọi mạng nằm ngoài transaction. */
    void reconcileInFlightSignatures();
}
