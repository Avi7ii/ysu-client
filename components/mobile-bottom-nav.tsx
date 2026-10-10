"use client"

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import { Capacitor } from "@capacitor/core"
import { usePathname, useRouter } from "next/navigation"
import { useTheme } from "next-themes"
import { Calendar, GraduationCap, LayoutDashboard, User } from "lucide-react"
import { cn } from "@/lib/utils"
import { useTranslation } from "@/lib/i18n/use-translation"
import { useSettingsStore } from "@/lib/stores/settings"
import { BOTTOM_NAV_ROUTES, getBottomNavIndex } from "@/lib/navigation/bottom-nav"
import {
  LiquidBottomNav,
  liquidBottomNavSupported,
  liquidNavThemeColors,
} from "@/lib/native/liquid-bottom-nav"

const LIQUID_NAV_SCALE = 0.9

export function MobileBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const { resolvedTheme } = useTheme()
  const { t } = useTranslation()
  const hasBackground = useSettingsStore((s) => !!s.backgroundImage)
  const style = useSettingsStore((s) => s.bottomNavStyle)
  const selectedIndex = getBottomNavIndex(pathname)
  const navRef = useRef<HTMLElement>(null)
  const [mobile, setMobile] = useState(
    () => typeof window !== "undefined" && window.matchMedia("(max-width: 767px)").matches
  )
  // Settings are hydrated by SDKProvider before this mounts. Reserve the chosen
  // native layout immediately, not one frame of the classic bar while the bridge loads.
  const [nativeActive, setNativeActive] = useState(
    () => style === "liquid" && Capacitor.getPlatform() === "android"
  )
  const [nativeHeight, setNativeHeight] = useState(() => {
    if (typeof document === "undefined") return 92 * LIQUID_NAV_SCALE
    const bottom = parseFloat(
      getComputedStyle(document.documentElement).getPropertyValue("--safe-area-inset-bottom")
    )
    return (
      64 * LIQUID_NAV_SCALE + (bottom > 0 ? bottom + 8 * LIQUID_NAV_SCALE : 28 * LIQUID_NAV_SCALE)
    )
  })
  const tabs = useMemo(
    () => [
      { href: "/dashboard", label: t("app.overview"), icon: LayoutDashboard },
      { href: "/dashboard/schedule", label: t("app.schedule"), icon: Calendar },
      { href: "/dashboard/grades", label: t("app.grades"), icon: GraduationCap },
      { href: "/dashboard/me", label: t("app.me"), icon: User },
    ],
    [t]
  )

  useEffect(() => {
    const media = window.matchMedia("(max-width: 767px)")
    const update = () => setMobile(media.matches)
    update()
    media.addEventListener("change", update)
    return () => media.removeEventListener("change", update)
  }, [])

  useEffect(() => {
    let cancelled = false
    const handles: { remove(): Promise<void> }[] = []
    const listen = async () => {
      if (!(await liquidBottomNavSupported()) || cancelled) return
      for (const handle of [
        await LiquidBottomNav.addListener("select", ({ index }) => {
          if (Number.isInteger(index) && index >= 0 && index < BOTTOM_NAV_ROUTES.length) {
            router.push(BOTTOM_NAV_ROUTES[index])
          }
        }),
        await LiquidBottomNav.addListener("height", ({ height }) => {
          if (Number.isFinite(height) && height > 0) setNativeHeight(height)
        }),
      ]) {
        if (cancelled) void handle.remove()
        else handles.push(handle)
      }
    }
    void listen().catch(() => {})
    return () => {
      cancelled = true
      handles.forEach((handle) => void handle.remove())
      void LiquidBottomNav.hide().catch(() => {})
    }
  }, [router])

  useEffect(() => {
    let cancelled = false
    const sync = async () => {
      if (!resolvedTheme) return
      const supported = await liquidBottomNavSupported()
      if (cancelled) return
      if (!supported) {
        setNativeActive(false)
        return
      }
      const visible = style === "liquid" && mobile
      if (!visible) setNativeActive(false)
      const result = await LiquidBottomNav.configure({
        visible,
        selectedIndex: Math.max(0, selectedIndex),
        labels: tabs.map((tab) => tab.label),
        dark: resolvedTheme === "dark",
        colors: liquidNavThemeColors(),
      })
      if (cancelled) return
      if (result.height > 0) setNativeHeight(result.height)
      setNativeActive(result.visible && visible)
    }
    void sync().catch((error) => {
      if (!cancelled) setNativeActive(false)
      console.warn("Native bottom navigation unavailable", error)
    })
    return () => {
      cancelled = true
    }
  }, [style, selectedIndex, pathname, tabs, mobile, resolvedTheme, hasBackground])

  useLayoutEffect(() => {
    const nav = navRef.current
    if (!nav) return
    const root = document.documentElement
    const updateHeight = () => {
      const height = nav.getBoundingClientRect().height
      const value = `${height}px`
      if (height > 0 && root.style.getPropertyValue("--mobile-bottom-nav-height") !== value) {
        root.style.setProperty("--mobile-bottom-nav-height", value)
      }
    }
    updateHeight()
    const observer = new ResizeObserver(updateHeight)
    observer.observe(nav, { box: "border-box" })
    return () => {
      observer.disconnect()
      root.style.removeProperty("--mobile-bottom-nav-height")
    }
  }, [])

  return (
    <nav
      ref={navRef}
      className={cn(
        "fixed inset-x-0 bottom-0 z-40 md:hidden",
        nativeActive
          ? "pointer-events-none"
          : [
              "grid grid-cols-4 border-t border-border pb-[var(--safe-area-inset-bottom,env(safe-area-inset-bottom,0px))] backdrop-blur",
              hasBackground
                ? "bg-background/60 supports-[backdrop-filter]:bg-background/40"
                : "bg-background/95 supports-[backdrop-filter]:bg-background/80",
            ]
      )}
      style={nativeActive ? { height: nativeHeight } : undefined}
      aria-hidden={nativeActive || undefined}
      aria-label="Primary"
    >
      {!nativeActive &&
        tabs.map((tab, index) => {
          const Icon = tab.icon
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={cn(
                "flex flex-col items-center justify-center gap-0.5 py-2 text-[10px] font-medium transition-colors",
                index === selectedIndex ? "text-primary" : "text-muted-foreground"
              )}
              aria-current={index === selectedIndex ? "page" : undefined}
            >
              <Icon aria-hidden="true" className="size-5" />
              <span>{tab.label}</span>
            </Link>
          )
        })}
    </nav>
  )
}
