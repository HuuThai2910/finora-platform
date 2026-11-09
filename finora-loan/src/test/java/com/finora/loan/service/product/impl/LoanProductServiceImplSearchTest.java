package com.finora.loan.service.product.impl;

import com.finora.loan.mapper.product.LoanProductMapper;
import com.finora.loan.repository.product.LoanProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ô tìm danh mục sản phẩm quản trị: tên hoặc mã, không phân biệt hoa thường. */
class LoanProductServiceImplSearchTest {

    private final LoanProductRepository products = mock(LoanProductRepository.class);
    private final LoanProductServiceImpl service =
            new LoanProductServiceImpl(products, mock(LoanProductMapper.class), Clock.systemUTC());

    @Test
    void searchPassesLowerCasePattern() {
        when(products.findAdminProducts(eq(null), eq(null), eq("%vay tiêu dùng%"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listAdmin(null, null, " Vay Tiêu Dùng ", 0, 20);

        verify(products).findAdminProducts(eq(null), eq(null), eq("%vay tiêu dùng%"), any(Pageable.class));
    }

    @Test
    void blankSearchIsNoFilter() {
        when(products.findAdminProducts(eq(null), eq(null), eq(null), any(Pageable.class))).thenReturn(Page.empty());

        service.listAdmin(null, null, "", 0, 20);

        verify(products).findAdminProducts(eq(null), eq(null), eq(null), any(Pageable.class));
    }
}
