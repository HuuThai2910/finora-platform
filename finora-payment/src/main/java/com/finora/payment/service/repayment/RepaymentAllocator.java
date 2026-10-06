package com.finora.payment.service.repayment;

import com.finora.payment.domain.servicing.PaymentNoteOwnership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RepaymentAllocator {
    public Map<Long, BigDecimal> allocate(BigDecimal amount, List<PaymentNoteOwnership> notes) {
        BigDecimal normalized = amount.setScale(2);
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        notes.forEach(note -> result.put(note.getNoteId(), BigDecimal.ZERO.setScale(2)));
        if (normalized.signum() == 0) return result;
        BigDecimal totalWeight = notes.stream().map(PaymentNoteOwnership::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) throw new IllegalArgumentException("Không có dư nợ Note để phân bổ");

        List<Share> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2);
        for (PaymentNoteOwnership note : notes) {
            BigDecimal exact = normalized.multiply(note.getOutstandingPrincipal())
                    .divide(totalWeight, 12, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(2, RoundingMode.DOWN);
            result.put(note.getNoteId(), floor);
            allocated = allocated.add(floor);
            shares.add(new Share(note.getNoteId(), exact.subtract(floor)));
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::noteId));
        int cents = normalized.subtract(allocated).movePointRight(2).intValueExact();
        for (int index = 0; index < cents; index++) {
            Long noteId = shares.get(index % shares.size()).noteId();
            result.put(noteId, result.get(noteId).add(new BigDecimal("0.01")));
        }
        return result;
    }

    private record Share(Long noteId, BigDecimal remainder) {}
}
