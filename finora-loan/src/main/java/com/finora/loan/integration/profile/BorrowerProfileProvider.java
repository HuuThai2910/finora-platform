package com.finora.loan.integration.profile;

import java.time.LocalDate;

/** Boundary có local mock và sẽ được thay bằng User Service adapter khi contract của Hải READY. */
public interface BorrowerProfileProvider {

    BorrowerProfileResult getBorrowerProfile(String borrowerId, LocalDate asOf);
}
