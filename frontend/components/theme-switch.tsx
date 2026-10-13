"use client";
import { useEffect, useState } from "react";
import { Icon, type IconName } from "./ui/icons";
import { DEFAULT_THEME, THEME_KEY as KEY } from "../lib/theme";

type Theme = "system" | "light" | "dark";
const choices: { value: Theme; label: string; icon: IconName }[] = [
  { value: "system", label: "跟随系统", icon: "monitor" },
  { value: "light", label: "浅色", icon: "sun" },
  { value: "dark", label: "深色", icon: "moon" },
];

function stored(): Theme {
  try {
    const value = localStorage.getItem(KEY);
    return value === "light" || value === "dark" || value === "system"
      ? value
      : DEFAULT_THEME;
  } catch {
    return DEFAULT_THEME;
  }
}

/** A per-browser appearance choice; storage failures fall back to the default theme. */
export function ThemeSwitch({ compact = false }: { compact?: boolean }) {
  const [theme, setTheme] = useState<Theme>(DEFAULT_THEME);
  useEffect(() => setTheme(stored()), []);
  function choose(next: Theme) {
    setTheme(next);
    const root = document.documentElement;
    if (next === "system") delete root.dataset.theme;
    else root.dataset.theme = next;
    try {
      localStorage.setItem(KEY, next);
    } catch {
      // The choice still applies to this page view.
    }
  }
  return (
    <div
      className={
        compact ? "segmented theme-switch compact" : "segmented theme-switch"
      }
      role="radiogroup"
      aria-label="外观"
    >
      {choices.map((choice) => (
        <button
          key={choice.value}
          type="button"
          role="radio"
          aria-checked={theme === choice.value}
          aria-label={choice.label}
          title={choice.label}
          onClick={() => choose(choice.value)}
        >
          <Icon name={choice.icon} />
          {!compact && choice.label}
        </button>
      ))}
    </div>
  );
}
