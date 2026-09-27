package com.finora.investment.service.messaging;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.investment.messaging.event.LoanContractActivatedEventData;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cập nhật projection Contract cho UI Investment; tuyệt đối không biến Investment thành owner Contract. */
@Service
@RequiredArgsConstructor
public class ContractLifecycleEventHandler {

    private final ProcessedEventRepository processedEventRepository;
    private final MarketListingRepository listingRepository;
    private final Clock clock;

    @Transactional
    public void handleSignatureRequested(
            UUID eventId,
            Instant occurredAt,
            InvestorSignatureRequestedEventData data
    ) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        requireText(data.contractNumber(), "contractNumber");
        requireHash(data.documentHash(), "documentHash");
        requireHash(data.allocationHash(), "allocationHash");
        MarketListing listing = lockedListing(data.listingId());
        requireSnapshot(listing, data.loanApplicationId(), data.applicationNumber(),
                data.allocationVersion(), data.allocationHash());
        if (listing.getContractNumber() != null
                && !listing.getContractNumber().equals(data.contractNumber())) {
            throw InvestmentDomainException.conflict(
                    "CONTRACT_REFERENCE_MISMATCH", "Listing đã gắn với Contract khác");
        }
        listing.setContractNumber(data.contractNumber());
        listing.setContractStatus("PENDING_LENDER_SIGNATURES");
        listing.setContractDocumentHash(data.documentHash());
        listing.setUpdatedBy("SYSTEM-LOAN-EVENT");
        listing.setUpdatedAt(clock.instant());
        listingRepository.saveAndFlush(listing);
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, "InvestorSignatureRequested", 1, "finora-loan",
                data.contractNumber(), occurredAt, clock.instant()));
    }

    @Transactional
    public void handleActivated(
            UUID eventId,
            Instant occurredAt,
            LoanContractActivatedEventData data
    ) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        requireText(data.contractNumber(), "contractNumber");
        requireHash(data.documentHash(), "documentHash");
        requireHash(data.pdfReceiptHash(), "pdfReceiptHash");
        requireHash(data.allocationHash(), "allocationHash");
        MarketListing listing = lockedListing(data.listingId());
        requireSnapshot(listing, data.loanApplicationId(), data.applicationNumber(),
                data.allocationVersion(), data.allocationHash());
        if (!data.contractNumber().equals(listing.getContractNumber())
                || !data.documentHash().equals(listing.getContractDocumentHash())) {
            throw InvestmentDomainException.conflict(
                    "CONTRACT_ACTIVATION_MISMATCH", "Contract kích hoạt không khớp projection đã nhận");
        }
        listing.setContractStatus("EFFECTIVE");
        listing.setContractReceiptHash(data.pdfReceiptHash());
        listing.setContractActivatedAt(data.activatedAt());
        listing.setUpdatedBy("SYSTEM-LOAN-EVENT");
        listing.setUpdatedAt(clock.instant());
        listingRepository.saveAndFlush(listing);
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, "LoanContractActivated", 1, "finora-loan",
                data.contractNumber(), occurredAt, clock.instant()));
    }

    private MarketListing lockedListing(Long listingId) {
        if (listingId == null) {
            throw new IllegalArgumentException("listingId không được null");
        }
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy listing của Contract event"));
    }

    private void requireSnapshot(
            MarketListing listing,
            Long loanApplicationId,
            String applicationNumber,
            Long allocationVersion,
            String allocationHash
    ) {
        if (listing.getStatus() != ListingStatus.FULLY_FUNDED
                || !listing.getLoanId().equals(loanApplicationId)
                || !listing.getApplicationNumber().equals(applicationNumber)
                || !listing.getAllocationVersion().equals(allocationVersion)
                || !listing.getAllocationHash().equals(allocationHash)) {
            throw InvestmentDomainException.conflict(
                    "CONTRACT_EVENT_LISTING_MISMATCH",
                    "Contract event không khớp allocation đã khóa của listing");
        }
    }

    private void requireHash(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " phải là SHA-256 chữ thường");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
    }
}
