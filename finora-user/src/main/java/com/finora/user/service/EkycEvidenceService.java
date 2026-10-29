package com.finora.user.service;

import com.finora.user.client.AiLivenessClient.EvidenceFrame;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.integration.storage.StoredObject;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Đưa bằng chứng eKYC lên kho lưu trữ và dọn chúng — chỉ gọi mạng, không chạm database.
 */
public interface EkycEvidenceService {

    /** Ảnh tĩnh tối đa mỗi lần gửi; dùng để đánh số {@code still-{n}} liên tục qua các lần gửi. */
    int MAX_STILL_FRAMES = 20;

    boolean isEnabled();

    /** Tiền tố object của môi trường này — phạm vi các câu quét dọn được phép xoá. */
    String envPrefix();

    /** Ảnh hai mặt thẻ đúng những byte đã gửi cho AI phân tích, tag {@code stage=pending}. */
    List<StoredEvidence> storeCardImages(long userProfileId, UUID attemptId, BinaryFile front, BinaryFile back);

    /**
     * Video (hoặc chuỗi ảnh tĩnh) của một lần gửi liveness cùng các khung bằng chứng AI trích ra.
     *
     * @param round số thứ tự lần gửi trong lượt, giữ cho object key không trùng giữa các lần quay lại
     */
    List<StoredEvidence> storeLiveness(long userProfileId, UUID attemptId, int round, BinaryFile video,
                                       List<byte[]> stills, List<EvidenceFrame> evidenceFrames);

    /** {@code result.json} của lượt đã có kết quả cuối — ghi thẳng giai đoạn {@code final}. */
    StoredEvidence storeResult(long userProfileId, UUID attemptId, byte[] json);

    /**
     * Đổi tag các object sang {@code final}. Best-effort: kho không nhận tag thì cột {@code stage} trong
     * DB vẫn là nguồn chuẩn cho worker quét dọn, nên không làm hỏng luồng người dùng.
     *
     * @return số object gắn tag thành công
     */
    int markFinal(Collection<String> keys);

    /**
     * Xoá từng object; object nào lỗi thì bỏ qua để worker quét dọn thử lại.
     *
     * @return key đã xoá được, để người gọi đánh dấu {@code deleted_at}
     */
    List<String> deleteObjects(Collection<String> keys);

    /** Xoá mọi object của người dùng trong môi trường này (rút đồng ý). */
    boolean deleteUserObjects(long userProfileId);

    StoredObject open(String key);

    /** Nội dung một file kèm kiểu và đuôi dùng trong object key. */
    record BinaryFile(byte[] bytes, String contentType, String extension) {
    }

    /** Metadata một object đã ghi thành công, chờ được lưu vào {@code ekyc_evidence}. */
    record StoredEvidence(EkycEvidenceKind kind, String label, String objectKey, String sha256,
                          long sizeBytes, String contentType, EkycEvidenceStage stage) {
    }
}
