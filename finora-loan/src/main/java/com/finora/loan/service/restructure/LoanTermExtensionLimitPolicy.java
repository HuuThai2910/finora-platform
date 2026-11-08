package com.finora.loan.service.restructure;

/** Tính hạn mức gia hạn còn lại mà không phụ thuộc hạ tầng, để UI và command dùng cùng một quy tắc. */
public final class LoanTermExtensionLimitPolicy {
    private LoanTermExtensionLimitPolicy() {}

    public static Result evaluate(int originalTerms, int completedExtraTerms,
            int maximumPerRequest, int maximumCumulativeExtraTerms, int maximumTotalTerms) {
        if (originalTerms < 0 || completedExtraTerms < 0 || maximumPerRequest <= 0
                || maximumCumulativeExtraTerms <= 0 || maximumTotalTerms <= 0) {
            throw new IllegalArgumentException("Các tham số hạn mức gia hạn không hợp lệ");
        }
        int currentTotalTerms = Math.addExact(originalTerms, completedExtraTerms);
        int maximumExtraTerms = Math.max(0, Math.min(maximumPerRequest,
                Math.min(maximumCumulativeExtraTerms - completedExtraTerms,
                        maximumTotalTerms - currentTotalTerms)));
        return new Result(currentTotalTerms, maximumExtraTerms);
    }

    public record Result(int currentTotalTerms, int maximumExtraTerms) {}
}
