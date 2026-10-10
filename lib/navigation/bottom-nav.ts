export const BOTTOM_NAV_STYLES = ["classic", "liquid"] as const
export type BottomNavStyle = (typeof BOTTOM_NAV_STYLES)[number]

export const BOTTOM_NAV_ROUTES = [
  "/dashboard",
  "/dashboard/schedule",
  "/dashboard/grades",
  "/dashboard/me",
] as const

export function normalizeBottomNavStyle(value: unknown): BottomNavStyle {
  return BOTTOM_NAV_STYLES.includes(value as BottomNavStyle) ? (value as BottomNavStyle) : "classic"
}

export function getBottomNavIndex(pathname: string): number {
  const path = pathname.replace(/\/$/, "")
  return BOTTOM_NAV_ROUTES.findIndex((href, index) =>
    index === 0 ? path === href : path === href || path.startsWith(`${href}/`)
  )
}
