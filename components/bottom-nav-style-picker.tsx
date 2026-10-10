"use client"

import { PanelBottom } from "lucide-react"
import { useEffect, useState } from "react"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useSettingsStore } from "@/lib/stores/settings"
import { useTranslation } from "@/lib/i18n/use-translation"
import { BOTTOM_NAV_STYLES, normalizeBottomNavStyle } from "@/lib/navigation/bottom-nav"
import { liquidBottomNavSupported } from "@/lib/native/liquid-bottom-nav"

const labels = {
  classic: "settings.bottomNavClassic",
  liquid: "settings.bottomNavLiquid",
}

export function BottomNavStylePicker() {
  const { t } = useTranslation()
  const style = useSettingsStore((s) => s.bottomNavStyle)
  const setStyle = useSettingsStore((s) => s.setBottomNavStyle)
  const [supported, setSupported] = useState<boolean | null>(null)
  useEffect(() => {
    let active = true
    void liquidBottomNavSupported().then((value) => {
      if (active) setSupported(value)
    })
    return () => {
      active = false
    }
  }, [])

  return (
    <div className="border-t border-border py-3">
      <div className="mb-3 flex items-start gap-3">
        <PanelBottom className="mt-0.5 size-5 shrink-0 text-muted-foreground" />
        <div>
          <p id="bottom-nav-style-label" className="text-sm">
            {t("settings.bottomNavStyle")}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">{t("settings.bottomNavStyleDesc")}</p>
        </div>
      </div>
      <ToggleGroup
        type="single"
        variant="outline"
        value={style}
        spacing={2}
        onValueChange={(value) => value && setStyle(normalizeBottomNavStyle(value))}
        aria-labelledby="bottom-nav-style-label"
        className="grid w-full grid-cols-2 gap-2"
      >
        {BOTTOM_NAV_STYLES.map((value) => (
          <ToggleGroupItem
            key={value}
            value={value}
            disabled={value === "liquid" && supported !== true}
            className="w-full text-xs"
          >
            {t(labels[value])}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {supported === false && (
        <p className="mt-2 text-xs text-muted-foreground">{t("settings.bottomNavUnsupported")}</p>
      )}
    </div>
  )
}
