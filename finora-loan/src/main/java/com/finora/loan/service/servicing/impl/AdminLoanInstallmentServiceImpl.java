package com.finora.loan.service.servicing.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.model.LoanServicingHistory;
import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse;
import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse.Installment;
import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse.InstallmentStatus;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.HistoryLoanRow;
import com.finora.loan.service.servicing.AdminLoanInstallmentService;
import com.finora.loan.service.statistics.PortfolioHistoryReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Trạng thái từng kỳ trả cho màn quản trị, đọc từ lịch hiện hành của Fineract.
 *
 * <p>Fineract ghi {@code obligationsMetOnDate} khi một kỳ được trả đủ, nên kỳ đã trả nhận ra trực tiếp từ core thay vì
 * suy từ tổng tiền đã trả. Kỳ chưa trả đủ thì so ngày đến hạn với ngày nghiệp vụ (giờ Việt Nam).</p>
 *
 * <p>Không mở transaction: hai truy vấn đọc nhỏ rồi lời gọi Fineract (qua bộ đọc có đệm) không được giữ kết nối DB.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLoanInstallmentServiceImpl implements AdminLoanInstallmentService {

    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final PortfolioHistoryReader portfolioHistoryReader;
    private final Clock clock;

    @Override
    public AdminLoanInstallmentsResponse installments(String loanNumber) {
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Finora Loan", "loanNumber", loanNumber));
        LoanServicingProjection projection =
                loanServicingProjectionRepository.findByFinoraLoanId(loan.getId()).orElse(null);
        LoanServicingHistory history = portfolioHistoryReader
                .read(List.of(new HistoryLoanRow(loan.getId(), loan.getFineractLoanId(), loan.getContractNumber(),
                        fingerprint(projection))))
                .get(loan.getId());
        if (history == null) {
            throw LoanBusinessException.serviceUnavailable("LOAN_INSTALLMENTS_UNAVAILABLE",
                    "Chưa đọc được lịch trả nợ từ hệ thống lõi, vui lòng thử lại sau");
        }
        return toResponse(loanNumber, history, LocalDate.now(clock));
    }

    static AdminLoanInstallmentsResponse toResponse(String loanNumber, LoanServicingHistory history, LocalDate today) {
        List<Installment> installments = new ArrayList<>();
        int paid = 0;
        int overdue = 0;
        // Lịch sử đã sắp theo ngày đến hạn; kỳ không có số (bản dựng chỉ có ngày) đánh số theo thứ tự.
        for (LoanServicingHistory.Installment installment : history.installments()) {
            InstallmentStatus status = statusOf(installment, today);
            if (status == InstallmentStatus.PAID) {
                paid++;
            } else if (status == InstallmentStatus.OVERDUE) {
                overdue++;
            }
            installments.add(new Installment(
                    installment.number() == null ? installments.size() + 1 : installment.number(),
                    installment.dueDate(), installment.totalDue(), installment.totalPaid(),
                    installment.obligationsMetOn(), status));
        }
        return new AdminLoanInstallmentsResponse(loanNumber, today, paid, overdue, installments);
    }

    private static InstallmentStatus statusOf(LoanServicingHistory.Installment installment, LocalDate today) {
        if (installment.obligationsMetOn() != null) {
            return InstallmentStatus.PAID;
        }
        if (installment.dueDate().isBefore(today)) {
            return InstallmentStatus.OVERDUE;
        }
        return installment.dueDate().isEqual(today) ? InstallmentStatus.DUE : InstallmentStatus.UPCOMING;
    }

    /**
     * Cùng dấu vân tay với {@code LoanStatisticsRepository.findLoansDisbursedBefore}: số đã trả, trạng thái core hoặc
     * ngày đáo hạn đổi thì bản đọc cũ trong đệm bị bỏ, nên kỳ vừa trả hiện ngay sau lần đồng bộ servicing kế tiếp.
     */
    private static String fingerprint(LoanServicingProjection projection) {
        if (projection == null) {
            return "null|null|null|null";
        }
        return String.join("|", String.valueOf(projection.getPrincipalPaid()),
                String.valueOf(projection.getInterestPaid()), String.valueOf(projection.getFineractStatusCode()),
                String.valueOf(projection.getMaturityDate()));
    }
}
