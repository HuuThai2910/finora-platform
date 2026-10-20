package com.finora.blockchain.domain.proof;

import java.util.regex.Pattern;

/**
 * Định dạng receipt theo provider (B50): receipt MOCK luôn có tiền tố {@code MOCK-} và block giả cố định, receipt Fabric
 * là transaction ID 64 hex của Fabric và block {@code <channel>/<số block>}. Hai loại không thể lẫn nhau vì định dạng
 * loại trừ nhau; database cũng chặn bằng check constraint cùng quy tắc (V2).
 */
public final class ProofReceiptFormat {

    public static final String MOCK_TRANSACTION_PREFIX = "MOCK-";
    public static final String MOCK_BLOCK_REFERENCE = "MOCK-BLOCK-NOT-ON-CHAIN";

    private static final Pattern FABRIC_TRANSACTION_ID = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern FABRIC_BLOCK_REFERENCE = Pattern.compile("^[a-z0-9][a-z0-9.-]{0,99}/[0-9]{1,19}$");

    private ProofReceiptFormat() {
    }

    public static boolean matches(ProofProviderType provider, String transactionId, String blockReference) {
        if (provider == null || transactionId == null) {
            return false;
        }
        return switch (provider) {
            case MOCK -> transactionId.startsWith(MOCK_TRANSACTION_PREFIX)
                    && MOCK_BLOCK_REFERENCE.equals(blockReference);
            case HYPERLEDGER_FABRIC -> FABRIC_TRANSACTION_ID.matcher(transactionId).matches()
                    && (blockReference == null || FABRIC_BLOCK_REFERENCE.matcher(blockReference).matches());
        };
    }

    /** Block reference chuẩn của Fabric để explorer hiển thị và người kiểm toán tra theo số block. */
    public static String fabricBlockReference(String channelName, long blockNumber) {
        return channelName + "/" + Long.toUnsignedString(blockNumber);
    }
}
