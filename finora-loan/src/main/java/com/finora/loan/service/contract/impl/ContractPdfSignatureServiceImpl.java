package com.finora.loan.service.contract.impl;

import com.finora.common.document.pades.EmbeddedPadesSignature;
import com.finora.common.document.pades.PadesCmsBuilder;
import com.finora.common.document.pades.PadesSignatureEmbedder;
import com.finora.common.document.pades.PadesSignatureLabel;
import com.finora.common.document.pades.PadesSignaturePreparer;
import com.finora.common.document.pades.PadesSignatureVerifier;
import com.finora.common.document.pades.PreparedPadesRevision;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.ContractPdfSignature;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfArtifactType;
import com.finora.loan.domain.contract.enums.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.enums.ContractPdfSignatureStatus;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.contract.model.ContractPdfSignatureSummary;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.client.SignatureProvider;
import com.finora.loan.integration.signature.contract.DigestSignatureCommand;
import com.finora.loan.integration.signature.contract.SignatureSubmission;
import com.finora.loan.integration.signature.contract.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.contract.SigningCertificate;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.ContractPdfSignatureRepository;
import com.finora.loan.repository.contract.InFlightPdfSignature;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.service.contract.ContractPdfSignatureService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chuỗi chữ ký PAdES của một Contract (R5, D13, D45–D47).
 *
 * <p>Mỗi lần ký được chuẩn bị trên bytes của bản SIGNED mới nhất (hoặc SIGNABLE khi chưa ai ký); sau khi nhúng, cả
 * chuỗi được kiểm lại trước khi lưu.</p>
 */
@Service
@RequiredArgsConstructor
public class ContractPdfSignatureServiceImpl implements ContractPdfSignatureService {

    private static final String LOCATION = "FINORA";

    private final ContractPdfSignatureRepository contractPdfSignatureRepository;
    private final LoanContractDocumentRepository loanContractDocumentRepository;
    private final ContractPartyRepository contractPartyRepository;
    private final PadesSignaturePreparer padesSignaturePreparer;
    private final PadesCmsBuilder padesCmsBuilder;
    private final PadesSignatureEmbedder padesSignatureEmbedder;
    private final PadesSignatureVerifier padesSignatureVerifier;
    private final SignatureProvider signatureProvider;
    private final HashingService hashingService;

    @Override
    @Transactional(readOnly = true)
    public ContractPdfSignature find(Long signatureId) {
        return contractPdfSignatureRepository.findById(signatureId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract PDF signature", "id", signatureId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> inFlight(Long contractId) {
        return contractPdfSignatureRepository.findFirstByContractIdAndStatus(
                contractId, ContractPdfSignatureStatus.PREPARED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> signedBy(Long contractId, ContractPartyType signerType, String signerId) {
        return contractPdfSignatureRepository.findFirstByContractIdAndSignerTypeAndSignerIdAndStatus(
                contractId, signerType, signerId, ContractPdfSignatureStatus.SIGNED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractPdfSignature> latestSigned(Long contractId) {
        return contractPdfSignatureRepository.findFirstByContractIdAndStatusOrderBySequenceNumberDesc(
                contractId, ContractPdfSignatureStatus.SIGNED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InFlightPdfSignature> dueInFlight(int limit) {
        return contractPdfSignatureRepository.findInFlight(
                ContractPdfSignatureStatus.PREPARED, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractPdfSignatureSummary> signedSummaries(Long contractId) {
        return contractPdfSignatureRepository.findSummaries(contractId, ContractPdfSignatureStatus.SIGNED);
    }

    @Override
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

    @Override
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
        int attempt = contractPdfSignatureRepository.maxAttemptNumber(
                lockedContract.getId(), signer.signerType(), signer.signerId()) + 1;
        PreparedPadesRevision prepared = padesSignaturePreparer.prepare(
                head.content(), label(lockedContract, signer, sequence), now);
        SigningCertificate certificate = signer.certificate();
        byte[] attributes = padesCmsBuilder.signedAttributes(prepared.documentDigest(), certificate.certificate());
        String attributesDigest = HexFormat.of().formatHex(padesCmsBuilder.digest(attributes));
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
        return contractPdfSignatureRepository.saveAndFlush(signature);
    }

    @Override
    public DigestSignatureCommand command(LoanContract contract, ContractPdfSignature signature) {
        return new DigestSignatureCommand(
                contract.getContractNumber(), signature.getSignerId(), signature.getSignedAttributesDigest(),
                signature.getProviderTransactionId(), signature.getProviderDocumentId(),
                "FINORA " + contract.getContractNumber() + " - chu ky " + signature.getSequenceNumber(),
                signature.getMethod());
    }

    @Override
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
        byte[] decodedSignature = decodeSignature(signatureValue);
        SigningCertificate certificate = SigningCertificate.fromEncoded(
                signature.certificateCopy(), signature.certificateChainCopy());
        byte[] attributes = signature.signedAttributesCopy();
        if (decodedSignature == null
                || !padesCmsBuilder.signatureMatches(attributes, decodedSignature, certificate.certificate())) {
            signature.abandon(ContractPdfSignatureAbandonReason.PROVIDER_SIGNATURE_INVALID, now);
            contractPdfSignatureRepository.saveAndFlush(signature);
            return new PadesCompletion(false, signature);
        }
        byte[] cms = padesCmsBuilder.assemble(
                attributes, decodedSignature, certificate.certificate(), certificate.chain());
        byte[] signed = padesSignatureEmbedder.embed(
                signature.contentCopy(), signature.getSignatureContentsOffset(),
                signature.getSignatureContentsLength(), cms);
        byte[] base = head.content();
        if (!Arrays.equals(signed, 0, base.length, base, 0, base.length)) {
            throw new IllegalStateException("Bản đã ký không giữ nguyên bản PDF trước làm tiền tố");
        }
        List<EmbeddedPadesSignature> verified = padesSignatureVerifier.verify(signed);
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
        return new PadesCompletion(true, contractPdfSignatureRepository.saveAndFlush(signature));
    }

    @Override
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(ContractPdfSignature signature, ContractPdfSignatureAbandonReason reason, Instant now) {
        if (!signature.isPrepared()) {
            return;
        }
        signature.abandon(reason, now);
        contractPdfSignatureRepository.saveAndFlush(signature);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandonInFlight(Long contractId, ContractPdfSignatureAbandonReason reason, Instant now) {
        inFlight(contractId).ifPresent(signature -> abandon(signature, reason, now));
    }

    @Override
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
        List<EmbeddedPadesSignature> verified = padesSignatureVerifier.verify(content);
        LoanContractDocument signable = signableDocument(lockedContract);
        LoanContractDocument document = loanContractDocumentRepository.findByContractIdAndArtifactType(
                        lockedContract.getId(), ContractPdfArtifactType.SIGNED_PDF)
                .orElseGet(() -> loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                        lockedContract.getId(), ContractPdfArtifactType.SIGNED_PDF, signable.getDocumentVersion(),
                        last.getContentHash(), content, now)));
        return new PublishedPadesDocument(signable, document, verified, signedSummaries(lockedContract.getId()));
    }

    private ChainHead head(LoanContract contract) {
        Optional<ContractPdfSignature> latest = latestSigned(contract.getId());
        long signedCount = contractPdfSignatureRepository.countByContractIdAndStatus(
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
        long lenders = contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId())
                .stream()
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
        return loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
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

    private static byte[] decodeSignature(String encodedSignature) {
        if (encodedSignature == null || encodedSignature.isBlank()) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(encodedSignature.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
