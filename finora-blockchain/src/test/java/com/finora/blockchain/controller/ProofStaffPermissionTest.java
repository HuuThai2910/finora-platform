package com.finora.blockchain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.blockchain.config.BlockchainSecurityConfig;
import com.finora.blockchain.service.proof.ProofQueryService;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofVerificationService;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền nhân viên trên explorer proof: xem và đối chiếu (verify chỉ đọc) cần quyền Xem Bằng chứng blockchain,
 * đưa proof lỗi về hàng đợi cần quyền Thao tác; ADMIN luôn qua. Service là mock nên "qua tầng bảo mật" nghĩa là không
 * bị 401/403.
 */
@WebMvcTest({ProofController.class, BlockchainController.class})
@Import({BlockchainSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class ProofStaffPermissionTest {

    private static final String PROOFS = "/api/v1/blockchain/proofs";
    private static final String PROOF_ID = "0b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f50";

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
    void blockchainViewerBrowsesAndVerifiesButCannotRetry() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.BLOCKCHAIN.view());

        perform(get(PROOFS), staff, passesSecurity());
        perform(get(PROOFS + "/" + PROOF_ID), staff, passesSecurity());
        perform(post(PROOFS + "/" + PROOF_ID + "/verify"), staff, passesSecurity());
        perform(post(PROOFS + "/" + PROOF_ID + "/retry"), staff, status().isForbidden());

        verify(submissionService, never()).requeue(any());
    }

    @Test
    void blockchainActorRetries() throws Exception {
        perform(post(PROOFS + "/" + PROOF_ID + "/retry"),
                staff(OpsFunction.BLOCKCHAIN.view(), OpsFunction.BLOCKCHAIN.action()), passesSecurity());

        verify(submissionService).requeue(PROOF_ID);
    }

    @Test
    void staffWithoutBlockchainIsDenied() throws Exception {
        perform(get(PROOFS), staff(), status().isForbidden());
        perform(post(PROOFS + "/" + PROOF_ID + "/verify"), staff(OpsFunction.RECONCILIATION.view()),
                status().isForbidden());
    }

    @Test
    void unmappedProofPathsAndActuatorStayAdminOnly() throws Exception {
        RequestPostProcessor everything = staff(OpsFunction.allPermissions().toArray(String[]::new));

        perform(post(PROOFS + "/" + PROOF_ID + "/unmapped"), everything, status().isForbidden());
        perform(get("/actuator/metrics"), everything, status().isForbidden());
    }

    @Test
    void adminPasses() throws Exception {
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        perform(get(PROOFS + "/" + PROOF_ID), admin, passesSecurity());
        perform(post(PROOFS + "/" + PROOF_ID + "/retry"), admin, passesSecurity());
    }

    private void perform(MockHttpServletRequestBuilder request, RequestPostProcessor auth, ResultMatcher expected)
            throws Exception {
        mvc.perform(request.with(auth)).andExpect(expected);
    }

    private static ResultMatcher passesSecurity() {
        return result -> assertThat(result.getResponse().getStatus())
                .as("%s %s", result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isNotIn(401, 403);
    }

    private static RequestPostProcessor staff(String... permissions) {
        GrantedAuthority[] authorities = new GrantedAuthority[permissions.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_STAFF");
        for (int i = 0; i < permissions.length; i++) {
            authorities[i + 1] = new SimpleGrantedAuthority(permissions[i]);
        }
        return jwt().jwt(token -> token.claim("user_id", "STAFF-1")).authorities(authorities);
    }
}
