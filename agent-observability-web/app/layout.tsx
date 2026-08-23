import type { Metadata } from "next";
import Link from "next/link";
import { LanguageSwitcher } from "../components/language-switcher";
import { currentLocale } from "../lib/current-locale";
import { dictionary } from "../lib/i18n";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agent 可观测性平台",
  description: "轻量级 Agent SDK Trace 控制台",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await currentLocale();
  const copy = dictionary(locale);

  return (
    <html lang={locale}>
      <body>
        <header className="topbar">
          <Link className="brand" href="/">
            <span className="brand-mark" aria-hidden="true">
              A
            </span>
            <span>
              <strong>Agent Observatory</strong>
              <small>{copy.brandSubtitle}</small>
            </span>
          </Link>
          <nav className="top-navigation" aria-label={copy.navigation.label}>
            <Link href="/">{copy.navigation.traces}</Link>
            <Link href="/applications">{copy.navigation.applications}</Link>
          </nav>
          <div className="topbar-actions">
            <div className="live-indicator">
              <span /> {copy.ingestionOnline}
            </div>
            <LanguageSwitcher locale={locale} label={copy.language} />
          </div>
        </header>
        <main className="shell">{children}</main>
      </body>
    </html>
  );
}
