package com.finora.loan.domain.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Lịch sử một khoản vay đọc từ Fineract, đủ để tính lại dư nợ gốc và số ngày quá hạn tại cuối một ngày
 * bất kỳ trong quá khứ. Không phải sổ dư nợ độc lập: chỉ là bản đọc của giao dịch và lịch trả trên core.
 *
 * <p>Dư nợ gốc cuối ngày = tiền giải ngân trừ phần gốc của các giao dịch chưa bị đảo, có ngày giao dịch
 * tới hết ngày đó. Số ngày quá hạn tính như đồng bộ servicing: kỳ sớm nhất đã tới hạn trước ngày đó mà
 * chưa trả đủ (Fineract ghi {@code obligationsMetOnDate} khi trả đủ một kỳ, nên biết được kỳ đó trả xong
 * từ ngày nào).</p>
 *
 * <p>Giới hạn: khoản đã cơ cấu chỉ còn lịch trả mới trên Fineract, nên số ngày quá hạn của các ngày trước
 * cơ cấu được tính theo lịch mới; dư nợ gốc vẫn đúng vì lấy từ giao dịch.</p>
 *
 * @param externalId mã ngoài trên Fineract (số hợp đồng FINORA), để đối chiếu đúng khoản vay
 * @param movements biến động gốc theo ngày: giải ngân dương, phần gốc được trả hoặc xóa âm
 * @param installments các kỳ trả (không gồm dòng giải ngân của lịch)
 */
public record LoanServicingHistory(String externalId, List<PrincipalMovement> movements,
        List<Installment> installments) {

    public LoanServicingHistory {
        movements = List.copyOf(movements);
        installments = installments.stream().sorted(Comparator.comparing(Installment::dueDate)).toList();
    }

    /**
     * Vị thế cuối ngày {@code day}; {@code null} khi khoản vay chưa giải ngân tới ngày đó.
     * Đã trả hết gốc thì trả vị thế 0 để nơi gọi bỏ qua.
     */
    public Position positionAt(LocalDate day) {
        boolean disbursed = false;
        BigDecimal principal = BigDecimal.ZERO;
        for (PrincipalMovement movement : movements) {
            if (movement.date().isAfter(day)) continue;
            disbursed |= movement.amount().signum() > 0;
            principal = principal.add(movement.amount());
        }
        if (!disbursed) return null;
        if (principal.signum() <= 0) return new Position(BigDecimal.ZERO, 0);
        return new Position(principal, daysPastDueAt(day));
    }

    /**
     * Nhóm nợ cuối ngày {@code day} theo D29: trong một đợt quá hạn liên tục, nhóm là nhóm cao nhất đã chạm. DPD chỉ giảm
     * vào ngày trả nợ, nên DPD lớn nhất của đợt nằm ở chính ngày đó hoặc trong một ngày trả của đợt, ngay trước khoản trả
     * (Loan quan sát được DPD này trong ngày, ví dụ 91 trước khi trả bớt về 61). Đợt kết thúc ở cuối một ngày trả không
     * còn quá hạn; không có khoản trả nào thì DPD không thể từ dương về 0.
     */
    public int debtGroupAt(LocalDate day) {
        int peak = daysPastDueAt(day);
        if (peak == 0) return DebtGroup.FIRST;
        List<LocalDate> payments = installments.stream().map(Installment::obligationsMetOn)
                .filter(met -> met != null && !met.isAfter(day)).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        for (LocalDate met : payments) {
            if (daysPastDueAt(met) == 0) break;
            peak = Math.max(peak, daysPastDueBeforePaymentOn(met));
        }
        return DebtGroup.fromDaysPastDue(peak);
    }

    /** DPD trong ngày {@code met} trước khoản trả của chính ngày đó: kỳ trả xong đúng ngày đó vẫn tính là chưa trả. */
    private int daysPastDueBeforePaymentOn(LocalDate met) {
        for (Installment installment : installments) {
            if (!installment.dueDate().isBefore(met)) return 0;
            LocalDate paid = installment.obligationsMetOn();
            if (paid == null || !paid.isBefore(met)) {
                return Math.toIntExact(ChronoUnit.DAYS.between(installment.dueDate(), met));
            }
        }
        return 0;
    }

    private int daysPastDueAt(LocalDate day) {
        for (Installment installment : installments) {
            if (!installment.dueDate().isBefore(day)) return 0;
            LocalDate met = installment.obligationsMetOn();
            if (met == null || met.isAfter(day)) {
                return Math.toIntExact(ChronoUnit.DAYS.between(installment.dueDate(), day));
            }
        }
        return 0;
    }

    public record PrincipalMovement(LocalDate date, BigDecimal amount) {}

    /** @param obligationsMetOn ngày kỳ được trả đủ; {@code null} khi chưa trả đủ */
    public record Installment(LocalDate dueDate, LocalDate obligationsMetOn) {}

    public record Position(BigDecimal principalOutstanding, int daysPastDue) {}
}
