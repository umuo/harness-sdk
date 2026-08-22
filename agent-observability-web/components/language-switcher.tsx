"use client";

import { useRouter } from "next/navigation";
import { LOCALE_COOKIE, type Locale } from "../lib/i18n";

export function LanguageSwitcher({
  locale,
  label,
}: {
  locale: Locale;
  label: string;
}) {
  const router = useRouter();

  function select(nextLocale: Locale) {
    document.cookie = `${LOCALE_COOKIE}=${nextLocale}; Path=/; Max-Age=31536000; SameSite=Lax`;
    router.refresh();
  }

  return (
    <div className="language-switcher" aria-label={label} role="group">
      <button
        className={locale === "zh-CN" ? "active" : ""}
        aria-pressed={locale === "zh-CN"}
        onClick={() => select("zh-CN")}
        type="button"
      >
        中文
      </button>
      <button
        className={locale === "en" ? "active" : ""}
        aria-pressed={locale === "en"}
        onClick={() => select("en")}
        type="button"
      >
        EN
      </button>
    </div>
  );
}
