"use client";

import { useMemo } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useTranslation } from "@/lib/i18n/use-translation";
import type { GPAStats, Grade } from "@/providers/types";

const DEGREE_COURSE_CREDIT_WEIGHT = 1.2;

function numericValue(value: number | undefined, fallback?: string): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  const parsed = Number(fallback);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function calculateWeightedGpa(grades: readonly Grade[]): string | undefined {
  let totalWeightedPoints = 0;
  let totalCredits = 0;

  for (const grade of grades) {
    if (!grade.isMajor || grade.isRetake !== "正考") continue;
    const gradePoint = numericValue(grade.numericGradePoint, grade.gradePoint);
    const credit = numericValue(grade.numericCredit, grade.credit);
    if (gradePoint === undefined || credit === undefined || credit <= 0) continue;

    const weightedCredit = credit * (grade.isDegreeCourse ? DEGREE_COURSE_CREDIT_WEIGHT : 1);
    totalWeightedPoints += gradePoint * weightedCredit;
    totalCredits += weightedCredit;
  }

  return totalCredits > 0 ? (totalWeightedPoints / totalCredits).toFixed(4) : undefined;
}

function formatChineseNumber(value: number): string {
  const digits = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"];
  if (value < 10) return digits[value] ?? String(value);
  if (value < 20) return `十${value === 10 ? "" : digits[value % 10]}`;
  if (value < 100) {
    const ones = value % 10;
    return `${digits[Math.floor(value / 10)]}十${ones === 0 ? "" : digits[ones]}`;
  }
  return String(value);
}

export function GpaSummary({
  gpa,
  grades,
  currentSemester,
}: {
  gpa: GPAStats | undefined;
  grades: readonly Grade[];
  currentSemester?: string;
}) {
  const { t, locale } = useTranslation();
  const items = useMemo(() => {
    const gradesBySemester = new Map<string, Grade[]>();
    for (const grade of grades) {
      if (!grade.semester) continue;
      const semesterGrades = gradesBySemester.get(grade.semester) ?? [];
      semesterGrades.push(grade);
      gradesBySemester.set(grade.semester, semesterGrades);
    }

    const semesterSet = new Set(gradesBySemester.keys());
    if (currentSemester) semesterSet.add(currentSemester);
    const semesters = Array.from(semesterSet).sort();
    const activeSemester = currentSemester ?? semesters[semesters.length - 1];

    const semesterItems = semesters.map((semester, index) => ({
      label:
        semester === activeSemester
          ? t("grades.currentSemesterGpa")
          : t("grades.semesterGpa", {
              number: locale === "zh" ? formatChineseNumber(index + 1) : index + 1,
            }),
      value: calculateWeightedGpa(gradesBySemester.get(semester) ?? []),
    }));

    return [
      ...semesterItems,
      {
        label: t("grades.totalGpa"),
        value: gpa?.gpaInitial || calculateWeightedGpa(grades),
      },
    ];
  }, [currentSemester, gpa?.gpaInitial, grades, locale, t]);

  return (
    <Card className="gap-1 py-3 md:gap-4 md:py-4">
      <CardHeader className="md:pb-3">
        <CardTitle className="text-sm md:text-base">{t("grades.gpaTitle")}</CardTitle>
        <CardDescription>{t("grades.gpaDescription")}</CardDescription>
      </CardHeader>
      <CardContent className="pt-0 md:pt-0">
        <div className="flex flex-col divide-y divide-border">
          {items.map((item) => (
            <div
              key={item.label}
              className="flex items-center justify-between py-2 text-sm md:py-2.5"
            >
              <span className="text-muted-foreground">{item.label}</span>
              <span className="font-semibold tabular-nums">{item.value || "-"}</span>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
