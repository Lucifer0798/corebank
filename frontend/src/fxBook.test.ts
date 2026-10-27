import { describe, expect, it } from "vitest";
import { describeUnrecognised, unrecognised } from "./fxBook";

describe("unrecognised", () => {
  it("is the mark less what the book is carried at, to the paisa", () => {
    expect(unrecognised({ markToMarket: 1234.56, markCarried: 1200 })).toBe(34.56);
    expect(unrecognised({ markToMarket: 1000, markCarried: 1250.25 })).toBe(-250.25);
    // Floating-point subtraction left alone reads as 0.30000000000000004.
    expect(unrecognised({ markToMarket: 0.3, markCarried: 0 })).toBe(0.3);
    expect(unrecognised({ markToMarket: 0.1 + 0.2, markCarried: 0 })).toBe(0.3);
  });
});

describe("describeUnrecognised", () => {
  it("names a gain, a loss, or nothing", () => {
    expect(describeUnrecognised(34.56)).toBe("gain");
    expect(describeUnrecognised(-250.25)).toBe("loss");
    expect(describeUnrecognised(0)).toBe("none");
  });
});
