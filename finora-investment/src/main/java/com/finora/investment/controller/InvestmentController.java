package com.finora.investment.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.CreateOrderRequest;
import com.finora.investment.dto.response.*;
import com.finora.investment.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/investments")
@RequiredArgsConstructor
public class InvestmentController {

    private final ListingService listingService;
    private final OrderService orderService;
    private final MatchingEngineService matchingEngine;
    private final FundingService fundingService;
    private final NoteService noteService;
    private final PortfolioService portfolioService;

    // ── Listings ──

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<ListingResponse> createListing(@Valid @RequestBody CreateListingRequest req) {
        var listing = listingService.create(req);
        var matches = matchingEngine.matchListing(listing);
        fundingService.processMatches(matches);
        return BaseResponse.created(ListingResponse.from(listing));
    }

    @GetMapping("/listings")
    public BaseResponse<PageResponse<ListingResponse>> getListings(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) BigDecimal minRate,
            @RequestParam(required = false) BigDecimal maxRate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = listingService.findOpen(grade, minRate, maxRate, pageable);

        var pageResp = PageResponse.<ListingResponse>builder()
                .content(result.getContent().stream().map(ListingResponse::from).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();

        return BaseResponse.success(pageResp);
    }

    // ── Orders ──

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest req) {
        var order = orderService.create(req);
        var matches = matchingEngine.matchOrder(order);
        fundingService.processMatches(matches);
        return BaseResponse.created(OrderResponse.from(order));
    }

    @GetMapping("/orders")
    public BaseResponse<PageResponse<OrderResponse>> getOrders(
            @RequestParam Long investorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = orderService.findByInvestor(investorId, pageable);

        var pageResp = PageResponse.<OrderResponse>builder()
                .content(result.getContent().stream().map(OrderResponse::from).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();

        return BaseResponse.success(pageResp);
    }

    @DeleteMapping("/orders/{id}")
    public BaseResponse<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancel(id);
        return BaseResponse.success("Huỷ lệnh thành công", null);
    }

    // ── Notes ──

    @GetMapping("/notes")
    public BaseResponse<PageResponse<NoteResponse>> getNotes(
            @RequestParam Long investorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("purchaseDate").descending());
        var result = noteService.findByInvestor(investorId, pageable);

        var pageResp = PageResponse.<NoteResponse>builder()
                .content(result.getContent().stream().map(NoteResponse::from).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();

        return BaseResponse.success(pageResp);
    }

    @GetMapping("/notes/{id}")
    public BaseResponse<NoteResponse> getNote(@PathVariable Long id) {
        return BaseResponse.success(NoteResponse.from(noteService.findById(id)));
    }

    // ── Portfolio ──

    @GetMapping("/portfolio")
    public BaseResponse<PortfolioResponse> getPortfolio(@RequestParam Long investorId) {
        return BaseResponse.success(portfolioService.getPortfolio(investorId));
    }

    // ── Funding ──

    @GetMapping("/listings/{id}/funding")
    public BaseResponse<FundingResponse> getFunding(@PathVariable Long id) {
        return BaseResponse.success(FundingResponse.from(listingService.findById(id)));
    }
}
