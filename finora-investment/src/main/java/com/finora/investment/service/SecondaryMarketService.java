package com.finora.investment.service;

import com.finora.investment.dto.request.ListNoteForSaleRequest;
import com.finora.investment.dto.response.NoteListingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Chợ thứ cấp Notes: treo bán, rút tin và mua lại Note của nhà đầu tư khác.
 *
 * <p>Xem plan {@code plans/INV-E1-secondary-market-flow.md} cho luồng nghiệp vụ và các quyết
 * định: trần giá bằng dư nợ gốc, phí 5% trừ người bán, cho bán Note nợ xấu kèm cảnh báo.</p>
 */
public interface SecondaryMarketService {

    /** Treo một Note mình đang giữ lên bảng tin. */
    NoteListingResponse listForSale(Long noteId, ListNoteForSaleRequest request);

    /** Người bán rút tin của mình khi chưa ai mua. */
    NoteListingResponse cancelListing(String listingReference);

    /** Mua một Note đang treo bán. */
    NoteListingResponse buy(String listingReference);

    /** Bảng tin: các Note đang được treo bán. */
    Page<NoteListingResponse> browse(Pageable pageable);

    /** Tin đăng bán của người đang đăng nhập. */
    Page<NoteListingResponse> myListings(Pageable pageable);
}
