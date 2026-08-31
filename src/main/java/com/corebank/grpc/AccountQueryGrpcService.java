package com.corebank.grpc;

import com.corebank.account.dto.AccountResponse;
import com.corebank.account.service.AccountSecurity;
import com.corebank.account.service.AccountService;
import com.corebank.grpc.proto.Account;
import com.corebank.grpc.proto.AccountQueryServiceGrpc;
import com.corebank.grpc.proto.GetAccountRequest;
import com.corebank.grpc.proto.ListCustomerAccountsRequest;
import com.corebank.grpc.proto.ListCustomerAccountsResponse;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The gRPC half of {@code AccountController}'s read endpoints. It calls the same
 * {@link AccountService} bean, so behaviour, Redis caching and the ledger rules stay in one
 * place rather than being reimplemented per surface.
 *
 * <p>Authorization goes through the same {@link AccountSecurity} bean the controllers'
 * {@code @PreAuthorize} expressions call, but invoked directly rather than through an annotation.
 * A {@code @PreAuthorize} here would have to parse the request's UUID inside a SpEL expression,
 * where a malformed one throws an evaluation error that surfaces as an opaque {@code UNKNOWN}
 * status; calling the bean after parsing lets a bad id be a clean {@code INVALID_ARGUMENT} and a
 * genuine denial a clean {@code PERMISSION_DENIED}. The rule being enforced is identical -- it is
 * the same method on the same bean.
 */
@GrpcService
public class AccountQueryGrpcService extends AccountQueryServiceGrpc.AccountQueryServiceImplBase {

    private final AccountService accountService;
    private final AccountSecurity accountSecurity;

    public AccountQueryGrpcService(AccountService accountService, AccountSecurity accountSecurity) {
        this.accountService = accountService;
        this.accountSecurity = accountSecurity;
    }

    @Override
    public void getAccount(GetAccountRequest request, StreamObserver<Account> observer) {
        UUID accountId = GrpcRequests.uuid(request.getAccountId(), "account_id");
        GrpcRequests.require(accountSecurity.canReadAccount(GrpcRequests.authentication(), accountId));

        AccountResponse account = accountService.get(accountId);
        observer.onNext(ProtoMapper.toProto(account));
        observer.onCompleted();
    }

    @Override
    public void listCustomerAccounts(ListCustomerAccountsRequest request,
                                     StreamObserver<ListCustomerAccountsResponse> observer) {
        UUID customerId = GrpcRequests.uuid(request.getCustomerId(), "customer_id");
        GrpcRequests.require(accountSecurity.canReadCustomer(GrpcRequests.authentication(), customerId));

        List<AccountResponse> accounts = accountService.listForCustomer(customerId);
        ListCustomerAccountsResponse.Builder response = ListCustomerAccountsResponse.newBuilder();
        accounts.forEach(account -> response.addAccounts(ProtoMapper.toProto(account)));
        observer.onNext(response.build());
        observer.onCompleted();
    }
}
