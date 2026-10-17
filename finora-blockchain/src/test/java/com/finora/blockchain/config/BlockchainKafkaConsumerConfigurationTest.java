package com.finora.blockchain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** B52: lỗi contract/xung đột đi DLT ngay; lỗi hạ tầng (DB) được thử lại có backoff trước khi vào DLT. */
class BlockchainKafkaConsumerConfigurationTest {

    @Test
    void contractAndConflictErrorsAreNotRetriedButDatabaseOutagesAre() {
        DefaultErrorHandler handler = new BlockchainKafkaConsumerConfiguration()
                .blockchainKafkaErrorHandler(mock(KafkaTemplate.class));

        // removeClassification trả classification trước đó: false = không retry.
        assertThat(handler.removeClassification(IllegalArgumentException.class)).isFalse();
        assertThat(handler.removeClassification(BusinessException.class)).isFalse();
        assertThat(handler.removeClassification(DataAccessResourceFailureException.class)).isNull();
    }
}
