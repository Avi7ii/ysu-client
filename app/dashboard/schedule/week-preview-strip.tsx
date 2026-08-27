"use client"

import { useEffect, useMemo, useRef } from "react"
import { useTranslation } from "@/lib/i18n/use-translation"
import { cn } from "@/lib/utils"
import type { ClassPeriod, Course, CurrentWeek } from "@/providers/types"
import { isCourseActiveInWeek, parseWeeks } from "./schedule-utils"

interface Props {
  courses: Course[]
  periods: ClassPeriod[]
  currentWeek: CurrentWeek | null
  selectedWeek: number
  nowMinutes: number
  onSelectWeek: (week: number) => void
  className?: string
}

const PREVIEW_DAYS = [1, 2, 3, 4, 5] as const

function buildSectionGroups(periods: ClassPeriod[]): number[][] {
  const sections = periods.map((period) => period.section).sort((a, b) => a - b)
  const available =
    sections.length > 0
      ? sections
      : Array.from({ length: 12 }, (_, index) => index + 1)
  return [
    available.filter((section) => section <= 2),
    available.filter((section) => section >= 3 && section <= 4),
    available.filter((section) => section >= 5 && section <= 6),
    available.filter((section) => section >= 7 && section <= 8),
    available.filter((section) => section >= 9),
  ]
}

function groupHasCourse(
  courses: Course[],
  week: number,
  day: number,
  group: number[]
) {
  if (group.length === 0) return false
  const first = group[0]
  const last = group[group.length - 1]
  return courses.some(
    (course) =>
      course.weekDay === day &&
      isCourseActiveInWeek(course, week) &&
      course.startSection <= last &&
      course.endSection >= first
  )
}

function groupIsPast(
  week: number,
  day: number,
  group: number[],
  currentWeek: CurrentWeek | null,
  nowMinutes: number,
  periods: ClassPeriod[]
) {
  if (!currentWeek?.week) return false
  if (week < currentWeek.week) return true
  if (week > currentWeek.week || day > currentWeek.weekday) return false
  if (day < currentWeek.weekday) return true

  const lastSection = group[group.length - 1]
  const lastPeriod = periods.find((period) => period.section === lastSection)
  const endMinute = lastPeriod?.endMinute
  if (Number.isFinite(endMinute)) return nowMinutes > Number(endMinute)
  if (!lastPeriod?.endTime) return false
  const [hour, minute] = lastPeriod.endTime.split(":").map(Number)
  return (
    Number.isFinite(hour) &&
    Number.isFinite(minute) &&
    nowMinutes > hour * 60 + minute
  )
}

// Visual structure independently follows XDYou's dual-licensed class-table week overview.
export function WeekPreviewStrip({
  courses,
  periods,
  currentWeek,
  selectedWeek,
  nowMinutes,
  onSelectWeek,
  className,
}: Props) {
  const { t } = useTranslation()
  const containerRef = useRef<HTMLDivElement>(null)
  const sectionGroups = useMemo(() => buildSectionGroups(periods), [periods])
  const weeks = useMemo(() => {
    const courseWeeks = courses.flatMap(
      (course) => course.weekList ?? parseWeeks(course.weeks || "")
    )
    const lastWeek = Math.max(
      20,
      selectedWeek,
      currentWeek?.week ?? 1,
      ...courseWeeks
    )
    return Array.from({ length: lastWeek }, (_, index) => index + 1)
  }, [courses, currentWeek?.week, selectedWeek])

  useEffect(() => {
    const selected = containerRef.current?.querySelector<HTMLElement>(
      `[data-week="${selectedWeek}"]`
    )
    selected?.scrollIntoView({
      behavior: "smooth",
      block: "nearest",
      inline: "center",
    })
  }, [selectedWeek])

  return (
    <div
      ref={containerRef}
      className={cn(
        "flex shrink-0 snap-x snap-mandatory gap-1.5 overflow-x-auto border-b border-border bg-background/75 px-2 py-2 backdrop-blur-md",
        className
      )}
      aria-label={t("schedule.weekLabel")}
    >
      {weeks.map((week) => {
        const active = week === selectedWeek
        const isCurrent = week === currentWeek?.week
        return (
          <button
            key={week}
            type="button"
            data-week={week}
            onClick={() => onSelectWeek(week)}
            className={cn(
              "flex w-[74px] shrink-0 snap-center flex-col items-center gap-1 rounded-lg border px-2 py-1.5 text-muted-foreground transition-[background-color,border-color,transform] active:scale-[0.97]",
              active
                ? "border-primary/35 bg-primary/10 text-foreground shadow-sm"
                : "border-transparent bg-background/45 hover:bg-accent/50"
            )}
            aria-pressed={active}
          >
            <span
              className={cn(
                "text-[11px] leading-none",
                isCurrent && "font-semibold text-primary"
              )}
            >
              {t("schedule.weekShort", { week })}
            </span>
            <span
              className="grid grid-cols-5 gap-x-1.5 gap-y-1"
              aria-hidden="true"
            >
              {sectionGroups.flatMap((group, groupIndex) =>
                PREVIEW_DAYS.map((day) => {
                  const occupied = groupHasCourse(courses, week, day, group)
                  const past =
                    occupied &&
                    groupIsPast(
                      week,
                      day,
                      group,
                      currentWeek,
                      nowMinutes,
                      periods
                    )
                  return (
                    <span
                      key={`${groupIndex}-${day}`}
                      className={cn(
                        "size-1 rounded-full bg-primary",
                        occupied
                          ? past
                            ? "opacity-45"
                            : "opacity-100"
                          : "opacity-20"
                      )}
                    />
                  )
                })
              )}
            </span>
          </button>
        )
      })}
    </div>
  )
}
