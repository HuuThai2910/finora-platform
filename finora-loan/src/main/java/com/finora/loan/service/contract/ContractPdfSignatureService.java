package com.finora.loan.service.contract;

import com.finora.common.document.pades.EmbeddedPadesSignature;
import com.finora.loan.domain.contract.entity.ContractPdfSignature;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.contract.model.ContractPdfSignatureSummary;
import com.finora.loan.integration.signature.contract.DigestSignatureCommand;
import com.finora.loan.integration.signature.contract.SigningCertificate;
import com.finora.loan.repository.contract.InFlightPdfSignature;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Chuỗi chữ ký PAdES của một Contract (R5, D13, D45–D47).
 *
 * <p>Mọi thao tác ghi chạy trong transaction của caller <b>sau khi caller đã khóa dòng Contract</b>
 * ({@code findByContractNumberForUpdate}). Khóa đó tuần tự hóa các bên: lần ký sau luôn được chuẩn bị trên bytes của
 * bản SIGNED trước, nên không có hai nhánh tài liệu. Chỉ có công việc CPU (PDFBox, BouncyCastle) trong lúc giữ khóa;
 * lời gọi mạng tới SmartCA luôn do caller thực hiện ngoài transaction.</p>
 */
public interface ContractPdfSignatureService {

    /** Lần ký theo ID, không có thì 404. */
    ContractPdfSignature find(Long signatureId);

    /** Lần ký đang chờ provider (PREPARED) của Contract, nếu có. */
    Optional<ContractPdfSignature> inFlight(Long contractId);

    /** Chữ ký đã nhúng của một bên ký trên Contract, nếu có. */
    Optional<ContractPdfSignature> signedBy(Long contractId, ContractPartyType signerType, String signerId);

    /** Bản SIGNED mới nhất (đầu chuỗi), có bytes; chỉ dùng khi cần tải hoặc ký tiếp. */
    Optional<ContractPdfSignature> latestSigned(Long contractId);

    /** Phiên đang chờ provider, cũ trước, giới hạn theo lượt worker; chỉ metadata, không bytes. */
    List<InFlightPdfSignature> dueInFlight(int limit);

    /** Metadata các chữ ký đã nhúng theo thứ tự chuỗi, không kéo bytes PDF. */
    List<ContractPdfSignatureSummary> signedSummaries(Long contractId);

    /**
     * Hash client được gửi khi đồng ý ký (D47): hash SIGNABLE (nội dung các bên đã đọc, client hiện hành gửi giá trị
     * này) hoặc đúng hash bản đầu chuỗi hiện tại. Mọi bản trong chuỗi có SIGNABLE làm tiền tố và chỉ thêm chữ ký ẩn,
     * nên hai giá trị cùng chỉ một nội dung hợp đồng.
     */
    boolean acceptsConsentHash(LoanContract contract, String signableHash, String requestedHash);

    /**
     * Chuẩn bị chữ ký tiếp theo của chuỗi và lưu PREPARED trước khi gửi hash cho provider.
     *
     * <p>Retry cùng bên ký và cùng yêu cầu nhận lại đúng lần ký đang chờ (cùng bytes, cùng digest, cùng transaction
     * provider) nên không sinh chữ ký thứ hai. Bên khác đang có phiên chờ thì trả 409 để client thử lại sau.</p>
     */
    ContractPdfSignature prepare(LoanContract lockedContract, PadesSignerRequest signer, Instant now);

    /** Lệnh gửi provider cho đúng lần ký đã lưu; retry dựng lại từ DB nên luôn cùng digest và transaction ID. */
    DigestSignatureCommand command(LoanContract contract, ContractPdfSignature signature);

    /**
     * Nhúng giá trị chữ ký provider trả về. Chữ ký được kiểm bằng khóa công khai của chứng thư trên đúng signed
     * attributes đã gửi; sai thì lần ký bị bỏ (ABANDONED) và không có byte nào được ghi vào chuỗi. Sau khi nhúng, toàn
     * bộ chuỗi được kiểm lại: số chữ ký đúng vị trí, mọi chữ ký trước vẫn hợp lệ, bản trước là tiền tố nguyên vẹn.
     */
    PadesCompletion complete(
            LoanContract lockedContract,
            ContractPdfSignature signature,
            String signatureValue,
            Instant now
    );

    /**
     * Provider ký ngay trong tiến trình (MOCK): chuẩn bị, ký và nhúng trong cùng transaction đang khóa Contract, nên
     * bên ký khác chỉ chờ khóa vài trăm mili giây thay vì gặp 409, và không bao giờ có phiên PREPARED tồn đọng.
     */
    ContractPdfSignature signSynchronously(LoanContract lockedContract, PadesSignerRequest signer, Instant now);

    /** Bỏ lần ký đang chờ với lý do cho trước; lần ký không còn PREPARED thì không làm gì. */
    void abandon(ContractPdfSignature signature, ContractPdfSignatureAbandonReason reason, Instant now);

    /** Đóng phiên đang chờ khi Contract hết hạn/bị từ chối để index một-phiên-chờ không giữ hợp đồng đã đóng. */
    void abandonInFlight(Long contractId, ContractPdfSignatureAbandonReason reason, Instant now);

    /**
     * Công bố bản PAdES đủ chữ ký (SIGNED_PDF) sau chữ ký cuối của người vay và trả các chữ ký đã kiểm để lập trang
     * biên nhận. Kiểm lại cả chuỗi từ bytes cuối thay vì tin metadata trong DB.
     */
    PublishedPadesDocument publishFinal(LoanContract lockedContract, Instant now);

    /** Yêu cầu ký PAdES của một bên; khóa idempotency là khóa ổn định đã chuẩn hóa của caller. */
    record PadesSignerRequest(
            ContractPartyType signerType,
            String signerId,
            SigningCertificate certificate,
            SignatureProviderType provider,
            SignatureMethod method,
            String idempotencyKey,
            String requestHash
    ) {
    }

    /** {@code signed = false}: provider trả chữ ký không khớp, lần ký đã chuyển ABANDONED và đã lưu. */
    record PadesCompletion(boolean signed, ContractPdfSignature signature) {
    }

    record PublishedPadesDocument(
            LoanContractDocument signable,
            LoanContractDocument signedPdf,
            List<EmbeddedPadesSignature> verifiedSignatures,
            List<ContractPdfSignatureSummary> signatures
    ) {
    }
}
