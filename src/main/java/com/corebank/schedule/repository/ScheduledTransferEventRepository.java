package com.corebank.schedule.repository;

import com.corebank.schedule.domain.ScheduledTransferEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Append-only, by construction: save and read, nothing that deletes. See ScheduleAttributionTest. */
public interface ScheduledTransferEventRepository extends Repository<ScheduledTransferEvent, UUID> {

    ScheduledTransferEvent save(ScheduledTransferEvent event);

    Page<ScheduledTransferEvent> findByScheduledTransferIdOrderByOccurredAtDesc(UUID scheduledTransferId,
                                                                               Pageable pageable);
}
