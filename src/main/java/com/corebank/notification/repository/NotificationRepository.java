package com.corebank.notification.repository;

import com.corebank.notification.domain.Notification;
import com.corebank.transaction.domain.TransactionStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Whether this account has already been told about this posting in this state -- the check that
     * turns a redelivered message into a no-op. Unique-constrained in V14 as well, which is the
     * guarantee; this is the cheap path that avoids tripping it in the ordinary case.
     */
    boolean existsByTransactionReferenceAndAccountIdAndTransactionStatus(
            String transactionReference, UUID accountId, TransactionStatus transactionStatus);

    Page<Notification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    long countByTransactionReference(String transactionReference);
}
