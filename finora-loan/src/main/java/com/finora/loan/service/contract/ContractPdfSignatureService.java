package com.finora.loan.service.contract;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.ContractPdfSignature;
import com.finora.loan.domain.contract.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.ContractPdfSignatureStatus;
import com.finora.loan.domain.contract.ContractPdfSignatureSummary;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.DigestSignatureCommand;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.integration.signature.SignatureSubmission;
import com.finora.loan.integration.signature.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.SigningCertificate;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.ContractPdfSignatureRepository;
import com.finora.loan.repository.contract.InFlightPdfSignature;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.service.contract.pades.EmbeddedPadesSignature;
import com.finora.loan.service.contract.pades.PadesCmsBuilder;
import com.finora.loan.service.contract.pades.PadesSignatureEmbedder;
import com.finora.loan.service.contract.pades.PadesSignatureLabel;
import com.finora.loan.service.contract.pades.PadesSignaturePreparer;
import com.finora.loan.service.contract.pades.PadesSignatureVerifier;
import com.finora.loan.service.contract.pades.PreparedPadesRevision;
import com.finora.loan.support.HashingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chuỗi chữ ký PAdES của một Contract (R5, D13, D45–D47).
 *
 * <p>Mọi thao tác ghi chạy trong transaction của caller <b>sau khi caller đã khóa dòng Contract</b>
 * ({@code findByContractNumberForUpdate}). Khóa đó tuần tự hóa các bên: lần ký sau luôn được chuẩn bị trên bytes của
 * bản SIGNED trước, nên không có hai nhánh tài liệu. Chỉ có công việc CPU (PDFBox, BouncyCastle) trong lúc giữ khóa;
 * lời gọi mạng tới SmartCA luôn do caller thực hiện ngoài transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class ContractPdfSignatureService {

    private static final String LOCATION = "FINORA";

    private final ContractPdfSignatureRepository signatureRepository;
    private final LoanContractDocumentRepository documentRepository;
    private final ContractPartyRepository partyRepository;
    private final PadesSignaturePreparer preparer;
    private final PadesCmsBuilder cmsBuilder;
    private final PadesSignatureEmbedder embedder;
    private final PadesSignatureVerifier verifier;
    private final SignatureProvider signatureProvider;
    private final HashingService hashingService;

    @Transactional(readOnly = true)
    public ContractPdfSignature find(Long signatureId) {
        return signatureRepository.findById(signatureId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract PDF signature", "id", signatureId));
    }

    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> inFlight(Long contractId) {
        return signatureRepository.findFirstByContractIdAndStatus(contractId, ContractPdfSignatureStatus.PREPARED);
    }

    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> signedBy(Long contractId, ContractPartyType signerType, String signerId) {
        return signatureRepository.findFirstByContractIdAndSignerTypeAndSignerIdAndStatus(
                contractId, signerType, signerId, ContractPdfSignatureStatus.SIGNED);
    }

    /** Bản SIGNED mới nhất (đầu chuỗi), có bytes; chỉ dùng khi cần tải hoặc ký tiếp. */
    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> latestSigned(Long contractId) {
        return signatureRepository.findFirstByContractIdAndStatusOrderBySequenceNumberDesc(
                contractId, ContractPdfSignatureStatus.SIGNED);
    }

    /** Phiên đang chờ provider, cũ trước, giới hạn theo lượt worker; chỉ metadata, không bytes. */
    @Transactional(readOnly = true)
    public List<InFlightPdfSignature> dueInFlight(int limit) {
        return signatureRepository.findInFlight(ContractPdfSignatureStatus.PREPARED, PageRequest.of(0, limit));
    }

    /** Metadata các chữ ký đã nhúng theo thứ tự chuỗi, không kéo bytes PDF. */
    @Transactional(readOnly = true)
    public List<ContractPdfSignatureSummary> signedSummaries(Long contractId) {
        return signatureRepository.findSummaries(contractId, ContractPdfSignatureStatus.SIGNED);
    }

    /**
     * Hash client được gửi khi đồng ý ký (D47): hash SIGNABLE (nội dung các bên đã đọc, client hiện hành gửi giá trị
     * này) hoặc đúng hash bản đầu chuỗi hiện tại. Mọi bản trong chuỗi có SIGNABLE làm tiền tố và chỉ thêm chữ ký ẩn,
     * nên hai giá trị cùng chỉ một nội dung hợp đồng.
     */
    @Transactional(readOnly = true)
    public boolean acceptsConsentHash(LoanContract contract, String signableHash, String requestedHash) {
        if (requestedHash == null || requestedHash.isBlank()) {
            return false;
        }
        if (requestedHash.equals(signableHash)) {
            return true;
        }
        if (!contract.isPadesSigned()) {
            return false;
        }
        List<ContractPdfSignatureSummary> signed = signedSummaries(contract.getId());
        return !signed.isEmpty() && signed.getLast().contentHash().equals(requestedHash);
    }

    /**
     * Chuẩn bị chữ ký tiếp theo của chuỗi và lưu PREPARED trước khi gửi hash cho provider.
     *
     * <p>Retry cùng bên ký và cùng yêu cầu nhận lại đúng lần ký đang chờ (cùng bytes, cùng digest, cùng transaction
     * provider) nên không sinh chữ ký thứ hai. Bên khác đang có phiên chờ thì trả 409 để client thử lại sau.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractPdfSignature prepare(LoanContract lockedContract, PadesSignerRequest signer, Instant now) {
        requirePades(lockedContract);
        Optional<ContractPdfSignature> pending = inFlight(lockedContract.getId());
        if (pending.isPresent()) {
            ContractPdfSignature current = pending.get();
            if (current.getSignerType() == signer.signerType() && current.getSignerId().equals(signer.signerId())
                    && current.matchesRequest(signer.idempotencyKey(), signer.requestHash())) {
                return current;
            }
            throw LoanBusinessException.conflict(
                    "CONTRACT_SIGNATURE_IN_PROGRESS",
                    "Một bên khác đang ký số hợp đồng này; vui lòng thử lại sau ít phút");
        }
        if (signedBy(lockedContract.getId(), signer.signerType(), signer.signerId()).isPresent()) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Bên ký đã có chữ ký trên hợp đồng này");
        }
        if (signer.signerType() == ContractPartyType.BORROWER) {
            requireAllLendersSigned(lockedContract);
        }
        ChainHead head = head(lockedContract);
        int sequence = head.signedCount() + 1;
        int attempt = signatureRepository.maxAttemptNumber(
                lockedContract.getId(), signer.signerType(), signer.signerId()) + 1;
        PreparedPadesRevision prepared = preparer.prepare(
                head.content(), label(lockedContract, signer, sequence), now);
        SigningCertificate certificate = signer.certificate();
        byte[] attributes = cmsBuilder.signedAttributes(prepared.documentDigest(), certificate.certificate());
        String attributesDigest = HexFormat.of().formatHex(cmsBuilder.digest(attributes));
        byte[] encodedCertificate = certificate.encodedCertificate();
        ContractPdfSignature signature = ContractPdfSignature.prepare(new ContractPdfSignature.Preparation(
                lockedContract.getId(), signer.signerType(), signer.signerId(), sequence, attempt,
                signer.provider(), signer.method(),
                transactionId(lockedContract, signer, attempt), "FINORA-" + attributesDigest.substring(0, 24),
                signer.idempotencyKey(), signer.requestHash(), head.contentHash(),
                prepared.content(), prepared.contentHash(), prepared.contentsOffset(), prepared.contentsLength(),
                prepared.documentDigestHex(), attributes, attributesDigest, prepared.signingTime(),
                encodedCertificate, certificate.encodedChain(), certificate.serialHex(),
                hashingService.sha256Bytes(encodedCertificate)), now);
        return signatureRepository.saveAndFlush(signature);
    }

    /** Lệnh gửi provider cho đúng lần ký đã lưu; retry dựng lại từ DB nên luôn cùng digest và transaction ID. */
    public DigestSignatureCommand command(LoanContract contract, ContractPdfSignature signature) {
        return new DigestSignatureCommand(
                contract.getContractNumber(), signature.getSignerId(), signature.getSignedAttributesDigest(),
                signature.getProviderTransactionId(), signature.getProviderDocumentId(),
                "FINORA " + contract.getContractNumber() + " - chu ky " + signature.getSequenceNumber(),
                signature.getMethod());
    }

    /**
     * Nhúng giá trị chữ ký provider trả về. Chữ ký được kiểm bằng khóa công khai của chứng thư trên đúng signed
     * attributes đã gửi; sai thì lần ký bị bỏ (ABANDONED) và không có byte nào được ghi vào chuỗi. Sau khi nhúng, toàn
     * bộ chuỗi được kiểm lại: số chữ ký đúng vị trí, mọi chữ ký trước vẫn hợp lệ, bản trước là tiền tố nguyên vẹn.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PadesCompletion complete(
            LoanContract lockedContract,
            ContractPdfSignature signature,
            String signatureValue,
            Instant now
    ) {
        if (signature.isSigned()) {
            return new PadesCompletion(true, signature);
        }
        if (!signature.isPrepared()) {
            throw LoanBusinessException.conflict(
                    "PADES_SIGNATURE_NOT_PREPARED", "Lần ký PDF đã bị hủy; vui lòng ký lại");
        }
        ChainHead head = head(lockedContract);
        if (!head.contentHash().equals(signature.getBaseContentHash())) {
            // Không thể xảy ra khi khóa Contract và index một-phiên-chờ còn hiệu lực; dừng thay vì nhúng lệch nhánh.
            throw new IllegalStateException("Bản PDF đầu chuỗi đã đổi so với lúc chuẩn bị chữ ký");
        }
        byte[] value = decodeSignature(signatureValue);
        SigningCertificate certificate = SigningCertificate.fromEncoded(
                signature.certificateCopy(), signature.certificateChainCopy());
        byte[] attributes = signature.signedAttributesCopy();
        if (value == null || !cmsBuilder.signatureMatches(attributes, value, certificate.certificate())) {
            signature.abandon(ContractPdfSignatureAbandonReason.PROVIDER_SIGNATURE_INVALID, now);
            signatureRepository.saveAndFlush(signature);
            return new PadesCompletion(false, signature);
        }
        byte[] cms = cmsBuilder.assemble(attributes, value, certificate.certificate(), certificate.chain());
        byte[] signed = embedder.embed(
                signature.contentCopy(), signature.getSignatureContentsOffset(),
                signature.getSignatureContentsLength(), cms);
        byte[] base = head.content();
        if (!Arrays.equals(signed, 0, base.length, base, 0, base.length)) {
            throw new IllegalStateException("Bản đã ký không giữ nguyên bản PDF trước làm tiền tố");
        }
        List<EmbeddedPadesSignature> verified = verifier.verify(signed);
        if (verified.size() != signature.getSequenceNumber()
                || !verified.getLast().certificateSha256().equals(signature.getCertificateSha256())) {
            throw new IllegalStateException("Chuỗi chữ ký sau khi nhúng không đúng thứ tự đã chuẩn bị");
        }
        String contentHash = hashingService.sha256Bytes(signed);
        String cmsHash = hashingService.sha256Bytes(cms);
        String evidenceHash = hashingService.sha256(new PadesEvidence(
                signature.getProvider(), signature.getMethod(), signature.getProviderTransactionId(),
                signature.getProviderDocumentId(), signature.getSequenceNumber(), contentHash, cmsHash,
                signature.getCertificateSha256(), signature.getSigningTime()));
        signature.markSigned(signed, contentHash, cmsHash, evidenceHash, now);
        return new PadesCompletion(true, signatureRepository.saveAndFlush(signature));
    }

    /**
     * Provider ký ngay trong tiến trình (MOCK): chuẩn bị, ký và nhúng trong cùng transaction đang khóa Contract, nên
     * bên ký khác chỉ chờ khóa vài trăm mili giây thay vì gặp 409, và không bao giờ có phiên PREPARED tồn đọng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractPdfSignature signSynchronously(LoanContract lockedContract, PadesSignerRequest signer, Instant now) {
        if (!signatureProvider.completesSynchronously()) {
            throw new IllegalStateException("Provider từ xa phải ký ngoài transaction");
        }
        ContractPdfSignature prepared = prepare(lockedContract, signer, now);
        SignatureSubmission submission = signatureProvider.submitDigest(command(lockedContract, prepared));
        if (submission.status() != SignatureSubmissionStatus.COMPLETED) {
            throw LoanBusinessException.serviceUnavailable(
                    "PADES_LOCAL_SIGNER_UNAVAILABLE", "Bộ ký cục bộ chưa trả chữ ký");
        }
        PadesCompletion completion = complete(lockedContract, prepared, submission.signatureValue(), now);
        if (!completion.signed()) {
            throw new IllegalStateException("Bộ ký cục bộ trả chữ ký không khớp chứng thư demo");
        }
        return completion.signature();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(ContractPdfSignature signature, ContractPdfSignatureAbandonReason reason, Instant now) {
        if (!signature.isPrepared()) {
            return;
        }
        signature.abandon(reason, now);
        signatureRepository.saveAndFlush(signature);
    }

    /** Đóng phiên đang chờ khi Contract hết hạn/bị từ chối để index một-phiên-chờ không giữ hợp đồng đã đóng. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandonInFlight(Long contractId, ContractPdfSignatureAbandonReason reason, Instant now) {
        inFlight(contractId).ifPresent(signature -> abandon(signature, reason, now));
    }

    /**
     * Công bố bản PAdES đủ chữ ký (SIGNED_PDF) sau chữ ký cuối của người vay và trả các chữ ký đã kiểm để lập trang
     * biên nhận. Kiểm lại cả chuỗi từ bytes cuối thay vì tin metadata trong DB.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedPadesDocument publishFinal(LoanContract lockedContract, Instant now) {
        requirePades(lockedContract);
        ContractPdfSignature last = latestSigned(lockedContract.getId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "PADES_SIGNATURES_MISSING", "Hợp đồng chưa có chữ ký PDF nào để công bố"));
        if (last.getSignerType() != ContractPartyType.BORROWER) {
            throw LoanBusinessException.conflict(
                    "PADES_BORROWER_SIGNATURE_MISSING", "Chữ ký cuối của chuỗi phải là của người vay");
        }
        byte[] content = last.contentCopy();
        requireIntegrity(content, last.getContentHash());
        List<EmbeddedPadesSignature> verified = verifier.verify(content);
        LoanContractDocument signable = signableDocument(lockedContract);
        LoanContractDocument document = documentRepository.findByContractIdAndArtifactType(
                        lockedContract.getId(), ContractPdfArtifactType.SIGNED_PDF)
                .orElseGet(() -> documentRepository.saveAndFlush(LoanContractDocument.create(
                        lockedContract.getId(), ContractPdfArtifactType.SIGNED_PDF, signable.getDocumentVersion(),
                        last.getContentHash(), content, now)));
        return new PublishedPadesDocument(signable, document, verified, signedSummaries(lockedContract.getId()));
    }

    private ChainHead head(LoanContract contract) {
        Optional<ContractPdfSignature> latest = latestSigned(contract.getId());
        long signedCount = signatureRepository.countByContractIdAndStatus(
                contract.getId(), ContractPdfSignatureStatus.SIGNED);
        if (latest.isPresent()) {
            ContractPdfSignature signature = latest.get();
            if (signature.getSequenceNumber() != signedCount) {
                throw new IllegalStateException("Chuỗi chữ ký PDF bị đứt quãng");
            }
            byte[] content = signature.contentCopy();
            requireIntegrity(content, signature.getContentHash());
            return new ChainHead(content, signature.getContentHash(), signature.getSequenceNumber());
        }
        LoanContractDocument signable = signableDocument(contract);
        byte[] content = signable.contentCopy();
        requireIntegrity(content, signable.getContentHash());
        return new ChainHead(content, signable.getContentHash(), 0);
    }

    private void requireAllLendersSigned(LoanContract contract) {
        long lenders = partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()).stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                .map(ContractParty::getPartyId)
                .distinct()
                .count();
        long signedLenders = signedSummaries(contract.getId()).stream()
                .filter(summary -> summary.signerType() == ContractPartyType.LENDER)
                .count();
        if (lenders == 0 || signedLenders != lenders) {
            throw LoanBusinessException.conflict(
                    "LENDER_SIGNATURES_INCOMPLETE", "Người vay chỉ ký sau khi mọi bên cho vay đã ký số");
        }
    }

    private LoanContractDocument signableDocument(LoanContract contract) {
        return documentRepository.findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract PDF", "contractNumber", contract.getContractNumber()));
    }

    private void requireIntegrity(byte[] content, String expectedHash) {
        if (!hashingService.sha256Bytes(content).equals(expectedHash)) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PDF_INTEGRITY_FAILED", "Bản PDF hợp đồng lưu trữ không vượt qua kiểm tra toàn vẹn");
        }
    }

    private static void requirePades(LoanContract contract) {
        if (!contract.isPadesSigned()) {
            throw new IllegalStateException("Contract không dùng hồ sơ ký PAdES");
        }
    }

    /** Vai trò + mã giả danh (như PHỤ LỤC 02) và cảnh báo giá trị pháp lý; không có họ tên hay số định danh. */
    private static PadesSignatureLabel label(LoanContract contract, PadesSignerRequest signer, int sequence) {
        String role = signer.signerType() == ContractPartyType.LENDER ? "Bên cho vay" : "Bên vay";
        String legalNotice = signer.provider() == SignatureProviderType.MOCK
                ? "chữ ký demo, không có giá trị pháp lý"
                : "VNPT SmartCA UAT, không có giá trị pháp lý production";
        return new PadesSignatureLabel(
                role + " " + signer.signerId(),
                "Ký Hợp đồng cho vay " + contract.getContractNumber() + " - chữ ký thứ " + sequence
                        + " (PAdES-B-B, " + legalNotice + ")",
                LOCATION);
    }

    /** Ổn định theo hợp đồng, bên ký, lần thử và khóa idempotency; VNPT yêu cầu transaction_id duy nhất. */
    private static String transactionId(LoanContract contract, PadesSignerRequest signer, int attempt) {
        String seed = contract.getContractNumber() + "|" + signer.signerType() + "|" + signer.signerId()
                + "|" + attempt + "|" + signer.idempotencyKey();
        String prefix = signer.provider() == SignatureProviderType.MOCK ? "MOCK-PADES-" : "FINORA-PADES-";
        return prefix + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decodeSignature(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Yêu cầu ký PAdES của một bên; khóa idempotency là khóa ổn định đã chuẩn hóa của caller. */
    public record PadesSignerRequest(
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
    public record PadesCompletion(boolean signed, ContractPdfSignature signature) {
    }

    public record PublishedPadesDocument(
            LoanContractDocument signable,
            LoanContractDocument signedPdf,
            List<EmbeddedPadesSignature> verifiedSignatures,
            List<ContractPdfSignatureSummary> signatures
    ) {
    }

    private record ChainHead(byte[] content, String contentHash, int signedCount) {
    }

    /** Dữ liệu được băm thành evidence hash của bên ký; gắn bằng chứng với đúng bản PDF và CMS đã nhúng. */
    private record PadesEvidence(
            SignatureProviderType provider,
            SignatureMethod method,
            String transactionId,
            String documentId,
            int sequence,
            String signedContentHash,
            String cmsHash,
            String certificateSha256,
            Instant signingTime
    ) {
    }
}
