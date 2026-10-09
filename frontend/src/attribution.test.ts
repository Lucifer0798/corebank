import { describe, expect, it } from "vitest";
import { postedBy } from "./attribution";

describe("postedBy", () => {
  it("names the member of staff by username", () => {
    expect(postedBy({ initiatedBy: { subject: "a1b2-teller", name: "ravi" } })).toBe("ravi");
  });

  it("falls back to the token subject when there is no username", () => {
    expect(postedBy({ initiatedBy: { subject: "a1b2-teller", name: null } })).toBe("a1b2-teller");
  });

  it("marks a job as a job, so its payment is not read as a person's", () => {
    expect(postedBy({ initiatedBy: { subject: "system:interest-runner", name: "interest-runner" } }))
      .toBe("System: interest-runner");
  });

  it("says plainly when a posting predates attribution, rather than showing a blank", () => {
    expect(postedBy({ initiatedBy: null })).toMatch(/Not recorded/);
  });
});
