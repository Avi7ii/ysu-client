import { describe, expect, it } from "vitest"
import { BOTTOM_NAV_STYLES, getBottomNavIndex, normalizeBottomNavStyle } from "./bottom-nav"

describe("bottom navigation preferences", () => {
  it("retains the original style for old or invalid saved preferences", () => {
    for (const value of [undefined, null, "", "unknown", "material", "miuix", {}, 2]) {
      expect(normalizeBottomNavStyle(value)).toBe("classic")
    }
    for (const value of ["classic", "liquid"]) {
      expect(normalizeBottomNavStyle(value)).toBe(value)
    }
  })

  it("highlights the parent destination, including nested and trailing-slash routes", () => {
    expect(getBottomNavIndex("/dashboard/")).toBe(0)
    expect(getBottomNavIndex("/dashboard/schedule")).toBe(1)
    expect(getBottomNavIndex("/dashboard/grades/")).toBe(2)
    expect(getBottomNavIndex("/dashboard/me/settings")).toBe(3)
    expect(getBottomNavIndex("/dashboard/training-plan")).toBe(-1)
    expect(getBottomNavIndex("/dashboard/grades-other")).toBe(-1)
  })

  it("offers only the two requested styles", () => {
    expect(BOTTOM_NAV_STYLES).toEqual(["classic", "liquid"])
  })
})
