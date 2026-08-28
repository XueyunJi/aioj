import type { DateValue } from "@internationalized/date";
import { parseDateTime } from "@internationalized/date";
import {
  DateField,
  DateInput,
  DateSegment,
  Group,
  Label
} from "react-aria-components";
import { CalendarDays, ChevronLeft, ChevronRight } from "lucide-react";
import { type ReactNode, useEffect, useId, useMemo, useState } from "react";
import { cn } from "@aioj/ui-react";
import { useI18n } from "../lib/i18n";

type CalendarGridDay = {
  year: number;
  month: number;
  day: number;
  key: string;
  inCurrentMonth: boolean;
};

export function DateTimeField({
  label,
  value,
  onChange,
  dateLabel,
  timeLabel,
  nowLabel,
  clearLabel,
  hint,
  disabled = false,
  allowClear = true,
  defaultTime = "09:00",
  className
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  dateLabel: string;
  timeLabel: string;
  nowLabel: string;
  clearLabel: string;
  hint?: ReactNode;
  disabled?: boolean;
  allowClear?: boolean;
  defaultTime?: string;
  className?: string;
}) {
  const id = useId();
  const fieldHelpId = `${id}-help`;
  const parsedValue = parseLocalDateTime(value);
  const placeholderValue = parseLocalDateTime(`${todayDatePart()}T${defaultTime}`);
  const [isCalendarOpen, setIsCalendarOpen] = useState(false);

  useEffect(() => {
    if (disabled) {
      setIsCalendarOpen(false);
    }
  }, [disabled]);

  return (
    <DateField
      value={parsedValue}
      placeholderValue={placeholderValue ?? undefined}
      onChange={(nextValue) => {
        onChange(formatLocalDateTime(nextValue));
      }}
      granularity="minute"
      hourCycle={24}
      shouldForceLeadingZeros
      isDisabled={disabled}
      aria-describedby={hint ? fieldHelpId : undefined}
      className={cn("relative min-w-0", className)}
    >
      <div className="mb-1.5 flex items-end justify-between gap-3">
        <Label className="text-sm font-medium text-[var(--oj-ink)]">{label}</Label>
        <span className="shrink-0 text-xs text-[var(--oj-ink-muted)]">{dateLabel} / {timeLabel}</span>
      </div>
      <div className="grid gap-2 rounded-xl border border-[var(--oj-border)] bg-white p-2 focus-within:border-[var(--oj-primary)] focus-within:ring-2 focus-within:ring-[var(--oj-focus)] sm:grid-cols-[minmax(0,1fr)_auto]">
        <Group className="flex min-h-10 min-w-0 items-center rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 transition-colors data-[focus-within]:border-[var(--oj-primary)] data-[focus-within]:bg-white">
          <DateInput className="flex min-w-0 flex-1 items-center gap-0.5 overflow-x-auto py-2 text-sm tabular-nums text-[var(--oj-ink)]">
            {(segment) => (
              <DateSegment
                segment={segment}
                className={({ isFocused, isPlaceholder }) => cn(
                  "rounded px-0.5 py-0.5 outline-none",
                  isPlaceholder && "text-[var(--oj-ink-muted)]",
                  isFocused && "bg-[var(--oj-primary)] text-white"
                )}
              />
            )}
          </DateInput>
          <button
            type="button"
            disabled={disabled}
            aria-label={`${label} ${dateLabel}`}
            onClick={() => setIsCalendarOpen((open) => !open)}
            className="ml-2 grid size-8 shrink-0 place-items-center rounded-lg text-[var(--oj-ink-muted)] outline-none transition-colors hover:bg-white hover:text-[var(--oj-primary)] disabled:cursor-not-allowed disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          >
            <CalendarDays className="size-4" aria-hidden="true" />
          </button>
        </Group>
        <div className="flex items-center gap-2 sm:justify-end">
          <button
            type="button"
            disabled={disabled}
            onClick={() => {
              onChange(toLocalMinute(new Date()));
              setIsCalendarOpen(false);
            }}
            className="inline-flex h-10 shrink-0 items-center justify-center rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm font-medium text-[var(--oj-ink)] outline-none transition-colors hover:bg-[var(--oj-surface-muted)] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          >
            {nowLabel}
          </button>
          {allowClear ? (
            <button
              type="button"
              disabled={disabled || !value}
              onClick={() => {
                onChange("");
                setIsCalendarOpen(false);
              }}
              className="inline-flex h-10 shrink-0 items-center justify-center rounded-lg px-3 text-sm font-medium text-[var(--oj-ink-muted)] outline-none transition-colors hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)] disabled:cursor-not-allowed disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
            >
              {clearLabel}
            </button>
          ) : null}
        </div>
      </div>
      {hint ? <span id={fieldHelpId} className="mt-1.5 block text-xs leading-5 text-[var(--oj-ink-muted)]">{hint}</span> : null}
      {isCalendarOpen ? (
        <div
          role="dialog"
          aria-label={`${label} ${dateLabel}`}
          className="absolute left-0 top-full z-50 mt-2 rounded-2xl border border-[var(--oj-border)] bg-white p-3 shadow-xl outline-none"
        >
          <CalendarButtonGrid
            value={parsedValue}
            placeholderValue={placeholderValue}
            defaultTime={defaultTime}
            onChange={(nextValue) => {
              onChange(nextValue);
              setIsCalendarOpen(false);
            }}
          />
        </div>
      ) : null}
    </DateField>
  );
}

function CalendarButtonGrid({
  value,
  placeholderValue,
  defaultTime,
  onChange
}: {
  value: DateValue | null;
  placeholderValue: DateValue | null;
  defaultTime: string;
  onChange: (value: string) => void;
}) {
  const { t, list } = useI18n();
  const anchorDate = value ?? placeholderValue;
  const anchorYear = anchorDate?.year ?? new Date().getFullYear();
  const anchorMonth = anchorDate?.month ?? new Date().getMonth() + 1;
  const [visibleMonth, setVisibleMonth] = useState(() => monthFromDateValue(anchorDate));

  useEffect(() => {
    setVisibleMonth({ year: anchorYear, month: anchorMonth });
  }, [anchorYear, anchorMonth]);

  const weeks = useMemo(() => buildCalendarWeeks(visibleMonth.year, visibleMonth.month), [visibleMonth]);
  const selectedKey = value ? dateKey(value.year, value.month, value.day) : "";
  const todayKey = todayDatePart();
  const commitDay = (day: CalendarGridDay) => {
    if (!day.inCurrentMonth) {
      return;
    }
    const nextValue = mergeDatePartsWithTime(day, value, defaultTime);
    onChange(nextValue);
  };

  return (
    <div className="w-[min(92vw,340px)]">
      <header className="mb-3 flex items-center justify-between gap-2">
        <button
          type="button"
          aria-label={t("common.calendar.prevMonth")}
          onClick={() => setVisibleMonth((month) => addMonths(month, -1))}
          className="grid size-9 place-items-center rounded-lg text-[var(--oj-ink-muted)] outline-none hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
        >
          <ChevronLeft className="size-4" aria-hidden="true" />
        </button>
        <div className="text-sm font-semibold text-[var(--oj-ink)]">
          {t("common.calendar.monthTitle", { year: visibleMonth.year, month: visibleMonth.month })}
        </div>
        <button
          type="button"
          aria-label={t("common.calendar.nextMonth")}
          onClick={() => setVisibleMonth((month) => addMonths(month, 1))}
          className="grid size-9 place-items-center rounded-lg text-[var(--oj-ink-muted)] outline-none hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
        >
          <ChevronRight className="size-4" aria-hidden="true" />
        </button>
      </header>
      <table className="w-full border-separate border-spacing-1">
        <thead>
          <tr>
            {list("common.calendar.weekdays").map((day, index) => (
              <th key={`${day}-${index}`} className="h-8 text-center text-xs font-medium text-[var(--oj-ink-muted)]">
                {day}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week) => (
            <tr key={week.map((day) => day.key).join("-")}>
              {week.map((day) => {
                const isSelected = day.key === selectedKey;
                const isToday = day.key === todayKey;
                return (
                  <td key={day.key}>
                    <button
                      type="button"
                      disabled={!day.inCurrentMonth}
                      onPointerDown={(event) => {
                        event.preventDefault();
                        commitDay(day);
                      }}
                      onClick={(event) => {
                        event.preventDefault();
                        commitDay(day);
                      }}
                      className={cn(
                        "grid size-9 place-items-center rounded-lg text-sm tabular-nums outline-none transition-colors",
                        !day.inCurrentMonth && "cursor-not-allowed text-[var(--oj-ink-soft)] opacity-50",
                        isToday && !isSelected && "font-semibold text-[var(--oj-primary)]",
                        isSelected && "bg-[var(--oj-primary)] font-semibold text-white",
                        day.inCurrentMonth && !isSelected && "hover:bg-[var(--oj-surface-muted)]",
                        "focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
                      )}
                    >
                      {day.day}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function todayDatePart() {
  return toLocalMinute(new Date()).slice(0, 10);
}

function toLocalMinute(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function parseLocalDateTime(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) {
    return null;
  }
  try {
    return parseDateTime(value);
  } catch {
    return null;
  }
}

function formatLocalDateTime(value: DateValue | null) {
  if (!value) {
    return "";
  }
  const hour = "hour" in value ? value.hour : 0;
  const minute = "minute" in value ? value.minute : 0;
  return `${value.year}-${pad2(value.month)}-${pad2(value.day)}T${pad2(hour)}:${pad2(minute)}`;
}

function mergeDatePartsWithTime(
  date: { year: number; month: number; day: number },
  current: DateValue | null,
  defaultTime: string
) {
  const fallback = parseDefaultTime(defaultTime);
  const hour = current && "hour" in current ? current.hour : fallback.hour;
  const minute = current && "minute" in current ? current.minute : fallback.minute;
  return `${date.year}-${pad2(date.month)}-${pad2(date.day)}T${pad2(hour)}:${pad2(minute)}`;
}

function parseDefaultTime(defaultTime: string) {
  const [rawHour, rawMinute] = defaultTime.split(":");
  const hour = Number(rawHour);
  const minute = Number(rawMinute);
  return {
    hour: Number.isFinite(hour) ? Math.min(23, Math.max(0, hour)) : 9,
    minute: Number.isFinite(minute) ? Math.min(59, Math.max(0, minute)) : 0
  };
}

function pad2(value: number) {
  return String(value).padStart(2, "0");
}

function monthFromDateValue(value: DateValue | null) {
  return {
    year: value?.year ?? new Date().getFullYear(),
    month: value?.month ?? new Date().getMonth() + 1
  };
}

function addMonths(month: { year: number; month: number }, amount: number) {
  const next = new Date(month.year, month.month - 1 + amount, 1);
  return { year: next.getFullYear(), month: next.getMonth() + 1 };
}

function buildCalendarWeeks(year: number, month: number) {
  const firstOfMonth = new Date(year, month - 1, 1);
  const mondayOffset = (firstOfMonth.getDay() + 6) % 7;
  const start = new Date(year, month - 1, 1 - mondayOffset);
  return Array.from({ length: 6 }, (_, weekIndex) =>
    Array.from({ length: 7 }, (_, dayIndex) => {
      const date = new Date(start);
      date.setDate(start.getDate() + weekIndex * 7 + dayIndex);
      const dateYear = date.getFullYear();
      const dateMonth = date.getMonth() + 1;
      const dateDay = date.getDate();
      return {
        year: dateYear,
        month: dateMonth,
        day: dateDay,
        key: dateKey(dateYear, dateMonth, dateDay),
        inCurrentMonth: dateMonth === month
      };
    })
  );
}

function dateKey(year: number, month: number, day: number) {
  return `${year}-${pad2(month)}-${pad2(day)}`;
}
