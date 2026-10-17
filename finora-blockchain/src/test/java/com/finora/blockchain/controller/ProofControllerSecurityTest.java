package com.finora.blockchain.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.blockchain.config.BlockchainSecurityConfig;
import com.finora.blockchain.service.proof.ProofQueryService;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofVerificationService;
import com.finora.common.dto.PageResponse;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B54: explorer proof chỉ cho ADMIN với token Keycloak; health mở để kiểm sống. Trước W3D service rơi vào user Basic
 * sinh ngẫu nhiên của Spring Boot nên không có cách gọi đúng bằng token.
 */
@WebMvcTest({ProofController.class, BlockchainController.class})
@Import({BlockchainSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class ProofControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProofQueryService queryService;
    @MockBean
    private ProofVerificationService verificationService;
    @MockBean
    private ProofSubmissionService submissionService;
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/api/v1/blockchain/health")).andExpect(status().isOk());
    }

    @Test
    void anonymousCallerCannotBrowseProofs() throws Exception {
        mvc.perform(get("/api/v1/blockchain/proofs")).andExpect(status().isUnauthorized());

        verify(queryService, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void borrowerAndInvestorAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/blockchain/proofs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVESTOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/blockchain/proofs/0b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f50/retry")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BORROWER"))))
                .andExpect(status().isForbidden());

        verify(submissionService, never()).requeue(any());
    }

    @Test
    void adminBrowsesProofsOfALoan() throws Exception {
        when(queryService.search("LOAN_APPLICATION", "123", null, 0, 20)).thenReturn(PageResponse.<com.finora.blockchain.dto.response.ProofResponse>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true).build());

        mvc.perform(get("/api/v1/blockchain/proofs").param("referenceType", "LOAN_APPLICATION").param("referenceId", "123")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        mvc.perform(get("/api/v1/blockchain/proofs").param("size", "500")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
    }
}
