package com.finora.user.service.impl;

import com.finora.user.client.AiLivenessClient.EvidenceFrame;
import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.integration.storage.EkycEvidenceStorage;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.integration.storage.EkycStorageKeys;
import com.finora.user.integration.storage.StoredObject;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.support.Sha256;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Đưa bằng chứng eKYC lên kho lưu trữ và dọn chúng — chỉ gọi mạng, không chạm database.
 * <p>
 * Người gọi ghi metadata ({@link StoredEvidence}) vào {@code ekyc_evidence} trong một transaction riêng
 * sau khi upload xong, để không giữ connection DB trong lúc chờ kho lưu trữ. Ghi nhiều object mà hỏng
 * giữa chừng thì xoá những object vừa ghi, tránh để lại file mồ côi không có dòng metadata nào trỏ tới.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EkycEvidenceServiceImpl implements EkycEvidenceService {

    private static final String JSON = "application/json";
    private static final String JPEG = "image/jpeg";

    private final EkycEvidenceStorage storage;
    private final EkycStorageProperties properties;

    @Override
    public boolean isEnabled() {
        return storage.isEnabled();
    }

    @Override
    public String envPrefix() {
        return EkycStorageKeys.envPrefix(properties.getEnv());
    }

    @Override
    public List<StoredEvidence> storeCardImages(long userProfileId, UUID attemptId, BinaryFile front, BinaryFile back) {
        List<StoredEvidence> stored = new ArrayList<>();
        try {
            stored.add(put(userProfileId, attemptId, EkycEvidenceKind.CCCD_FRONT, null,
                    EkycStorageKeys.cardImageFile(EkycStorageKeys.CCCD_FRONT, front.extension()), front,
                    EkycEvidenceStage.PENDING));
            stored.add(put(userProfileId, attemptId, EkycEvidenceKind.CCCD_BACK, null,
                    EkycStorageKeys.cardImageFile(EkycStorageKeys.CCCD_BACK, back.extension()), back,
                    EkycEvidenceStage.PENDING));
            return stored;
        } catch (EkycStorageException e) {
            rollbackUploads(stored);
            throw e;
        }
    }

    @Override
    public List<StoredEvidence> storeLiveness(long userProfileId, UUID attemptId, int round, BinaryFile video,
                                              List<byte[]> stills, List<EvidenceFrame> evidenceFrames) {
        List<StoredEvidence> stored = new ArrayList<>();
        try {
            if (video != null) {
                stored.add(put(userProfileId, attemptId, EkycEvidenceKind.LIVENESS_VIDEO, null,
                        EkycStorageKeys.livenessVideoFile(round, video.extension()), video, EkycEvidenceStage.PENDING));
            }
            for (int i = 0; i < stills.size(); i++) {
                int sequence = (round - 1) * MAX_STILL_FRAMES + i + 1;
                stored.add(put(userProfileId, attemptId, EkycEvidenceKind.LIVENESS_FRAME, "STILL",
                        EkycStorageKeys.stillFile(sequence), new BinaryFile(stills.get(i), JPEG, "jpg"),
                        EkycEvidenceStage.PENDING));
            }
            Set<String> usedLabels = new HashSet<>();
            for (EvidenceFrame frame : evidenceFrames == null ? List.<EvidenceFrame>of() : evidenceFrames) {
                byte[] jpeg = decodeFrame(frame);
                if (jpeg == null) {
                    continue;
                }
                String label = uniqueLabel(EkycStorageKeys.sanitizeLabel(frame.label()), usedLabels);
                stored.add(put(userProfileId, attemptId, EkycEvidenceKind.LIVENESS_FRAME, label,
                        EkycStorageKeys.livenessFrameFile(label, round), new BinaryFile(jpeg, JPEG, "jpg"),
                        EkycEvidenceStage.PENDING));
            }
            return stored;
        } catch (EkycStorageException e) {
            rollbackUploads(stored);
            throw e;
        }
    }

    @Override
    public StoredEvidence storeResult(long userProfileId, UUID attemptId, byte[] json) {
        return put(userProfileId, attemptId, EkycEvidenceKind.RESULT, null, EkycStorageKeys.RESULT_FILE,
                new BinaryFile(json, JSON, "json"), EkycEvidenceStage.FINAL);
    }

    @Override
    public int markFinal(Collection<String> keys) {
        int tagged = 0;
        for (String key : keys) {
            if (storage.setStage(key, EkycEvidenceStage.FINAL)) {
                tagged++;
            }
        }
        return tagged;
    }

    @Override
    public List<String> deleteObjects(Collection<String> keys) {
        List<String> deleted = new ArrayList<>();
        for (String key : keys) {
            try {
                storage.delete(key);
                deleted.add(key);
            } catch (EkycStorageException e) {
                log.warn("Chưa xoá được object bằng chứng, worker sẽ thử lại: key={}", key);
            }
        }
        return deleted;
    }

    @Override
    public boolean deleteUserObjects(long userProfileId) {
        String prefix = EkycStorageKeys.userPrefix(properties.getEnv(), userProfileId);
        try {
            int count = storage.deletePrefix(prefix);
            log.info("Đã xoá bằng chứng eKYC theo tiền tố người dùng: userProfileId={}, objects={}",
                    userProfileId, count);
            return true;
        } catch (EkycStorageException e) {
            log.warn("Chưa xoá hết bằng chứng eKYC của người dùng, worker sẽ thử lại: userProfileId={}",
                    userProfileId);
            return false;
        }
    }

    @Override
    public StoredObject open(String key) {
        return storage.open(key);
    }

    private StoredEvidence put(long userProfileId, UUID attemptId, EkycEvidenceKind kind, String label,
                               String fileName, BinaryFile file, EkycEvidenceStage stage) {
        String key = EkycStorageKeys.objectKey(properties.getEnv(), userProfileId, attemptId, fileName);
        storage.put(key, file.bytes(), file.contentType(), stage);
        return new StoredEvidence(kind, label, key, Sha256.hex(file.bytes()), file.bytes().length,
                file.contentType(), stage);
    }

    private void rollbackUploads(List<StoredEvidence> stored) {
        deleteObjects(stored.stream().map(StoredEvidence::objectKey).toList());
    }

    /** Khung AI gửi hỏng (không phải base64 JPEG) thì bỏ qua: thiếu một khung không làm mất video gốc. */
    private static byte[] decodeFrame(EvidenceFrame frame) {
        if (frame == null || frame.jpegB64() == null || frame.jpegB64().isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(frame.jpegB64().trim());
            if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8 || (bytes[2] & 0xFF) != 0xFF) {
                log.warn("Khung bằng chứng liveness không phải JPEG, bỏ qua: label={}", frame.label());
                return null;
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            log.warn("Khung bằng chứng liveness không giải mã được, bỏ qua: label={}", frame.label());
            return null;
        }
    }

    private static String uniqueLabel(String label, Set<String> used) {
        String candidate = label;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = label + "_" + suffix++;
        }
        return candidate;
    }
}
