import { afterEach, describe, expect, it, vi } from "vitest"
import { renderToStaticMarkup } from "react-dom/server"
import { createElement } from "react"

const saved = vi.hoisted(() => ({ style: "liquid", platform: "android" }))
vi.mock("@capacitor/core", () => ({
  Capacitor: { getPlatform: () => saved.platform },
  registerPlugin: () => ({}),
}))
vi.mock("next/navigation", () => ({
  usePathname: () => "/dashboard",
  useRouter: () => ({}),
}))
vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme: "dark" }) }))
vi.mock("@/lib/i18n/use-translation", () => ({ useTranslation: () => ({ t: (s: string) => s }) }))
vi.mock("@/lib/stores/settings", () => ({
  useSettingsStore: (select: (s: object) => unknown) =>
    select({ bottomNavStyle: saved.style, backgroundImage: "" }),
}))

import { MobileBottomNav } from "@/components/mobile-bottom-nav"

afterEach(() => {
  saved.style = "liquid"
  saved.platform = "android"
  vi.unstubAllGlobals()
})

describe("bottom navigation first paint", () => {
  it("reserves the liquid layout without briefly rendering classic links", () => {
    vi.stubGlobal("window", { matchMedia: () => ({ matches: true }) })
    vi.stubGlobal("document", { documentElement: {} })
    vi.stubGlobal("getComputedStyle", () => ({ getPropertyValue: () => "24px" }))
    const html = renderToStaticMarkup(createElement(MobileBottomNav))
    expect(html).not.toContain("<a")
    expect(html).toContain("height:88.8px")
    expect(html).toContain('aria-hidden="true"')
  })

  it("keeps the original navigation on web and for the classic preference", () => {
    saved.platform = "web"
    expect(renderToStaticMarkup(createElement(MobileBottomNav))).toContain("<a")
    saved.platform = "android"
    saved.style = "classic"
    expect(renderToStaticMarkup(createElement(MobileBottomNav))).toContain("<a")
  })
})
