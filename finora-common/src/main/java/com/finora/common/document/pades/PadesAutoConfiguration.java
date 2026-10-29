package com.finora.common.document.pades;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Bean ký PAdES dùng chung cho Loan (hợp đồng vay) và Investment (giấy xác nhận chuyển nhượng Note).
 *
 * <p>PDFBox và BouncyCastle là dependency optional của common: service nào không tự khai báo chúng thì cấu hình này
 * không bật, nên các service khác vẫn scan {@code com.finora.common} mà không thiếu class.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.apache.pdfbox.pdmodel.PDDocument",
        "org.bouncycastle.asn1.cms.SignedData"
})
public class PadesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PadesSignaturePreparer padesSignaturePreparer() {
        return new PadesSignaturePreparer();
    }

    @Bean
    @ConditionalOnMissingBean
    PadesCmsBuilder padesCmsBuilder() {
        return new PadesCmsBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    PadesSignatureEmbedder padesSignatureEmbedder() {
        return new PadesSignatureEmbedder();
    }

    @Bean
    @ConditionalOnMissingBean
    PadesSignatureVerifier padesSignatureVerifier() {
        return new PadesSignatureVerifier();
    }
}
