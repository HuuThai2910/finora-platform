package com.finora.user.integration.cic.client;

import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cic-mapping-service", url = "${finora.cic.url:http://localhost:9000}")
public interface CicMappingClient {
    @PostMapping("/api/v1/internal/borrower-mappings")
    void register(
            @RequestHeader("X-Finora-Internal-Key") String internalApiKey,
            @RequestBody RegisterBorrowerMappingRequest request);
}
