package com.corebank.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * ObjectMapper is mocked rather than real here on purpose: whether the JSON this actually
 * produces round-trips through a real consumer's deserializer is exactly what
 * CoreBankTestcontainersIT's own tests already prove against a real broker. This is only about
 * whether write() builds the row correctly from whatever the mapper hands back.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("writes a row carrying the aggregate type, topic, key and serialized payload")
    void writesARowWithEveryField() {
        OutboxEventWriter writer = new OutboxEventWriter(repository, objectMapper);
        Object payload = new Object();
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"reference\":\"TXN-1\"}");

        writer.write(OutboxAggregateType.TRANSACTION, "corebank.transactions.posted", "TXN-1", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo(OutboxAggregateType.TRANSACTION);
        assertThat(saved.getTopic()).isEqualTo("corebank.transactions.posted");
        assertThat(saved.getEventKey()).isEqualTo("TXN-1");
        assertThat(saved.getPayload()).isEqualTo("{\"reference\":\"TXN-1\"}");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getAttempts()).isZero();
    }

    @Test
    @DisplayName("a serialization failure propagates rather than being swallowed")
    void serializationFailurePropagates() {
        OutboxEventWriter writer = new OutboxEventWriter(repository, objectMapper);
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("cannot serialize"));

        assertThatThrownBy(() -> writer.write(OutboxAggregateType.CUSTOMER, "topic", "key", new Object()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("cannot serialize");
    }
}
