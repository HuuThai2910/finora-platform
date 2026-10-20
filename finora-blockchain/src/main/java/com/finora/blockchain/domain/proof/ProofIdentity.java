package com.finora.blockchain.domain.proof;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Định danh proof xác định theo mốc nghiệp vụ (B53): cùng {@code proofType + aggregateType + aggregateId} luôn ra cùng
 * proofId (UUID v5, RFC 9562). Event của cùng một mốc được phát lại với eventId mới, DB bị dựng lại hoặc worker gửi
 * lại sau timeout đều trỏ về đúng một bản ghi trên PostgreSQL và trên Fabric.
 */
public final class ProofIdentity {

    /** Namespace cố định của FINORA; đổi giá trị này sẽ làm mọi proofId mới khác proofId đã neo. */
    static final UUID NAMESPACE = UUID.fromString("5b0f8d2e-7c41-4f6a-9d3e-2a8c6b1f4e70");

    private ProofIdentity() {
    }

    public static UUID forMilestone(ProofType proofType, String aggregateType, String aggregateId) {
        if (proofType == null || aggregateType == null || aggregateId == null) {
            throw new IllegalArgumentException("Thiếu thành phần định danh mốc proof");
        }
        return nameBasedV5(proofType.name() + "|" + aggregateType + "|" + aggregateId);
    }

    private static UUID nameBasedV5(String name) {
        byte[] hash;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits())
                    .array());
            hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Runtime thiếu SHA-1", exception);
        }
        // UUID v5: 16 byte đầu của SHA-1, đặt version 5 và variant RFC 4122.
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
