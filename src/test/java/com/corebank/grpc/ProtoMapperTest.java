package com.corebank.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.EntryDirection;
import com.corebank.grpc.proto.Transaction;
import com.corebank.grpc.proto.TransactionLeg;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.dto.TransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What gRPC clients receive for a posting: in particular, each leg's own currency. */
class ProtoMapperTest {

    private static TransactionResponse.Leg leg(String number, EntryDirection direction, String amount, String currency) {
        return new TransactionResponse.Leg(UUID.randomUUID(), number, direction, new BigDecimal(amount), currency,
                new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("each leg of a cross-currency posting carries its own currency over gRPC")
    void legsCarryTheirCurrency() {
        TransactionResponse posting = new TransactionResponse(UUID.randomUUID(), "TXN-FX", TransactionType.TRANSFER,
                TransactionStatus.POSTED, new BigDecimal("10000.00"), "INR", "To USD", Instant.parse("2026-10-24T10:00:00Z"),
                null, null,
                List.of(leg("100100000001", EntryDirection.DEBIT, "10000.00", "INR"),
                        leg("GL0000000010", EntryDirection.CREDIT, "10000.00", "INR"),
                        leg("GL0000000011", EntryDirection.DEBIT, "119.40", "USD"),
                        leg("100100000002", EntryDirection.CREDIT, "119.40", "USD")),
                null);

        Transaction proto = ProtoMapper.toProto(posting);

        assertThat(proto.getCurrency()).isEqualTo("INR");
        assertThat(proto.getLegsList()).extracting(TransactionLeg::getCurrency)
                .containsExactly("INR", "INR", "USD", "USD");
    }
}
