package com.corebank.schedule.domain;

import java.time.LocalDate;

/**
 * How often a mandate falls due. Each value knows how to place its own occurrences, which is what
 * keeps {@link ScheduledTransfer} from growing a switch over frequencies.
 */
public enum ScheduleFrequency {

    ONCE {
        @Override
        public LocalDate occurrence(LocalDate startsOn, int index) {
            return index == 0 ? startsOn : null;
        }
    },

    DAILY {
        @Override
        public LocalDate occurrence(LocalDate startsOn, int index) {
            return startsOn.plusDays(index);
        }
    },

    WEEKLY {
        @Override
        public LocalDate occurrence(LocalDate startsOn, int index) {
            return startsOn.plusWeeks(index);
        }
    },

    MONTHLY {
        @Override
        public LocalDate occurrence(LocalDate startsOn, int index) {
            return startsOn.plusMonths(index);
        }
    };

    /**
     * The date of the {@code index}-th occurrence, counting the first as zero, or null when this
     * frequency has no such occurrence.
     *
     * <p>Every occurrence is measured from {@code startsOn} rather than from the previous due
     * date, and that is the whole reason this takes an index. Repeatedly adding a month to the
     * last date lets a mandate drift: a standing order starting 31 January would land on 28
     * February, then 28 March, and stay on the 28th for good, quietly moving a customer's rent
     * payment three days earlier forever. Anchored to the start date it clamps only in the short
     * months and returns to the 31st in the long ones, which is what the instruction actually
     * said and what a bank does.
     */
    public abstract LocalDate occurrence(LocalDate startsOn, int index);
}
