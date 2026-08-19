package com.finora.user.support;

import com.finora.user.config.CryptoProperties;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter — tự động encrypt khi ghi DB và decrypt khi đọc.
 * Áp dụng từng field bằng @Convert(converter = CryptoConverter.class), không autoApply,
 * vì chỉ một số field PII cần mã hóa (CCCD, số điện thoại, ...).
 */
@Component
@Converter(autoApply = false)
@RequiredArgsConstructor
public class CryptoConverter implements AttributeConverter<String, String> {

    private final CryptoProperties cryptoProperties;

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        return CryptoUtils.encryptAesGcm(plaintext, cryptoProperties.getAesSecret());
    }

    @Override
    public String convertToEntityAttribute(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        return CryptoUtils.decryptAesGcm(ciphertext, cryptoProperties.getAesSecret());
    }
}
