package com.finora.user.integration.storage;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Quy ước object key bằng chứng: {@code ekyc/{env}/{userProfileId}/{attemptPublicId}/{file}}.
 * <p>
 * Key chỉ chứa ID nội bộ và UUID của lượt — <b>không</b> có số định danh, họ tên hay email — vì key
 * xuất hiện trong log của kho lưu trữ, log ứng dụng và bảng {@code ekyc_evidence}. Tiền tố theo người
 * dùng cho phép xoá toàn bộ khi rút đồng ý bằng một lệnh liệt kê theo prefix.
 */
public final class EkycStorageKeys {

    private static final Pattern ENV_PATTERN = Pattern.compile("[a-z0-9-]{1,20}");
    private static final Pattern LABEL_PATTERN = Pattern.compile("[^A-Z0-9_]");

    public static final String CCCD_FRONT = "cccd-front";
    public static final String CCCD_BACK = "cccd-back";
    public static final String RESULT_FILE = "result.json";

    private EkycStorageKeys() {
    }

    /** Kiểm tra đoạn môi trường; sai thì dừng ứng dụng lúc khởi động thay vì ghi key lạ. */
    public static String requireValidEnv(String env) {
        if (env == null || !ENV_PATTERN.matcher(env).matches()) {
            throw new IllegalStateException(
                    "finora.ekyc.storage.env chỉ gồm chữ thường, số, dấu gạch (1-20 ký tự): " + env);
        }
        return env;
    }

    /** {@code ekyc/{env}/} — phạm vi object mà instance này được phép xoá. */
    public static String envPrefix(String env) {
        return "ekyc/" + env + "/";
    }

    /** {@code ekyc/{env}/{userProfileId}/} — xoá toàn bộ bằng chứng của một người dùng. */
    public static String userPrefix(String env, long userProfileId) {
        return envPrefix(env) + userProfileId + "/";
    }

    public static String objectKey(String env, long userProfileId, UUID attemptPublicId, String fileName) {
        return userPrefix(env, userProfileId) + attemptPublicId + "/" + fileName;
    }

    /** Ảnh thẻ: {@code cccd-front.jpg|png}. */
    public static String cardImageFile(String side, String extension) {
        return side + "." + extension;
    }

    /** Video liveness lần gửi thứ {@code round}: {@code liveness-{n}.mp4|mov}. */
    public static String livenessVideoFile(int round, String extension) {
        return "liveness-" + round + "." + extension;
    }

    /** Khung bằng chứng AI trích ra: {@code liveness-frame-{label}-{n}.jpg}. */
    public static String livenessFrameFile(String label, int round) {
        return "liveness-frame-" + sanitizeLabel(label) + "-" + round + ".jpg";
    }

    /** Ảnh tĩnh chế độ STILLS, đánh số liên tục qua các lần gửi: {@code still-{n}.jpg}. */
    public static String stillFile(int sequence) {
        return "still-" + sequence + ".jpg";
    }

    /** Nhãn khung hình chỉ giữ A-Z, 0-9, gạch dưới để không chen ký tự lạ vào key. */
    public static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "FRAME";
        }
        String cleaned = LABEL_PATTERN.matcher(label.trim().toUpperCase(Locale.ROOT)).replaceAll("_");
        return cleaned.length() <= 24 ? cleaned : cleaned.substring(0, 24);
    }
}
