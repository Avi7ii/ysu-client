import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core"

export interface LiquidNavConfig {
  visible: boolean
  selectedIndex: number
  labels: string[]
  dark: boolean
  colors: { primary: string; foreground: string; surface: string }
}

interface LiquidBottomNavPlugin {
  available(): Promise<{ supported: boolean }>
  configure(config: LiquidNavConfig): Promise<{ visible: boolean; height: number }>
  hide(): Promise<void>
  setObscured(options: { obscured: boolean }): Promise<void>
  addListener(
    event: "select",
    listener: (event: { index: number }) => void
  ): Promise<PluginListenerHandle>
  addListener(
    event: "height",
    listener: (event: { height: number }) => void
  ): Promise<PluginListenerHandle>
}

export const LiquidBottomNav = registerPlugin<LiquidBottomNavPlugin>("LiquidBottomNav")
let support: Promise<boolean> | undefined

export function liquidBottomNavSupported(): Promise<boolean> {
  if (Capacitor.getPlatform() !== "android") return Promise.resolve(false)
  return (support ??= LiquidBottomNav.available()
    .then((r) => r.supported)
    .catch(() => false))
}

let obscuredLayers = 0

/** Modal lifecycle integration, not DOM polling: native controls sit above the WebView. */
export function obscureLiquidBottomNav(): () => void {
  if (Capacitor.getPlatform() !== "android") return () => {}
  obscuredLayers++
  void LiquidBottomNav.setObscured({ obscured: true }).catch(() => {})
  return () => {
    obscuredLayers = Math.max(0, obscuredLayers - 1)
    void LiquidBottomNav.setObscured({ obscured: obscuredLayers > 0 }).catch(() => {})
  }
}

/** Resolve existing CSS theme roles to Android-compatible color strings. */
export function liquidNavThemeColors(): LiquidNavConfig["colors"] {
  const style = getComputedStyle(document.documentElement)
  const canvas = document.createElement("canvas")
  canvas.width = canvas.height = 1
  const ctx = canvas.getContext("2d")
  const color = (token: string, fallback: string) => {
    if (!ctx) return fallback
    ctx.clearRect(0, 0, 1, 1)
    ctx.fillStyle = fallback
    ctx.fillStyle = style.getPropertyValue(token).trim() || fallback
    ctx.fillRect(0, 0, 1, 1)
    const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
    return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`
  }
  return {
    primary: color("--primary", "#222222"),
    foreground: color("--foreground", "#111111"),
    surface: color("--muted", "#eeeeee"),
  }
}
