package com.finora.loan.integration.cic;

/** Lỗi relay được tách permanent/temporary để Kafka không retry request sai hợp đồng. */
public class CicDelinquencyRelayException extends RuntimeException {
    protected CicDelinquencyRelayException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class Permanent extends CicDelinquencyRelayException {
        public Permanent(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Temporary extends CicDelinquencyRelayException {
        public Temporary(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
