package com.corebank.grpc;

import com.corebank.account.service.AccountSecurity;
import com.corebank.grpc.proto.GetTransactionRequest;
import com.corebank.grpc.proto.StatementLine;
import com.corebank.grpc.proto.StreamStatementRequest;
import com.corebank.grpc.proto.Transaction;
import com.corebank.grpc.proto.TransactionQueryServiceGrpc;
import com.corebank.transaction.dto.StatementLineResponse;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.service.TransactionService;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The gRPC half of {@code TransactionController}'s read endpoints, over the same
 * {@link TransactionService} bean. See {@code AccountQueryGrpcService} for why ownership checks
 * call {@link AccountSecurity} directly instead of going through {@code @PreAuthorize}.
 */
@GrpcService
public class TransactionQueryGrpcService extends TransactionQueryServiceGrpc.TransactionQueryServiceImplBase {

    /**
     * The statement stream is read from the service a page at a time and emitted line by line.
     * Paging the reads keeps a long statement from being materialised in memory all at once,
     * which is the thing a streaming RPC is supposed to avoid; the page size is an internal
     * detail the caller never sees, unlike the REST endpoint's own `size` parameter.
     */
    private static final int STREAM_PAGE_SIZE = 200;

    private final TransactionService transactionService;
    private final AccountSecurity accountSecurity;

    public TransactionQueryGrpcService(TransactionService transactionService, AccountSecurity accountSecurity) {
        this.transactionService = transactionService;
        this.accountSecurity = accountSecurity;
    }

    @Override
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    public void getTransaction(GetTransactionRequest request, StreamObserver<Transaction> observer) {
        String reference = GrpcRequests.required(request.getReference(), "reference");
        TransactionResponse transaction = transactionService.getByReference(reference);
        observer.onNext(ProtoMapper.toProto(transaction));
        observer.onCompleted();
    }

    @Override
    public void streamStatement(StreamStatementRequest request, StreamObserver<StatementLine> observer) {
        UUID accountId = GrpcRequests.uuid(request.getAccountId(), "account_id");
        GrpcRequests.require(accountSecurity.canReadAccount(GrpcRequests.authentication(), accountId));

        Instant from = GrpcRequests.optionalInstant(request.getFrom(), "from");
        Instant to = GrpcRequests.optionalInstant(request.getTo(), "to");

        int pageNumber = 0;
        Page<StatementLineResponse> page;
        do {
            page = transactionService.statement(accountId, from, to, PageRequest.of(pageNumber, STREAM_PAGE_SIZE));
            for (StatementLineResponse line : page.getContent()) {
                observer.onNext(ProtoMapper.toProto(line));
            }
            pageNumber++;
        } while (!page.isLast());

        observer.onCompleted();
    }
}
