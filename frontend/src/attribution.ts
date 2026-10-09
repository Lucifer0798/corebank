import type { Transaction } from "./api/types";

const SYSTEM_PREFIX = "system:";

/**
 * Who made a posting, in words for the transaction page.
 *
 * A job reads as one, so a standing order's payment is not mistaken for something a person did; a
 * posting from before attribution was recorded says so rather than showing a blank that looks like
 * a fault.
 */
export function postedBy(transaction: Pick<Transaction, "initiatedBy">): string {
  const initiator = transaction.initiatedBy;
  if (!initiator) {
    return "Not recorded (made before postings were attributed)";
  }
  if (initiator.subject.startsWith(SYSTEM_PREFIX)) {
    return `System: ${initiator.name ?? initiator.subject.slice(SYSTEM_PREFIX.length)}`;
  }
  return initiator.name ?? initiator.subject;
}
