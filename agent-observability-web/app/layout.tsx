import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agent Observatory",
  description: "A lightweight trace console for Agent SDK",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="topbar">
          <Link className="brand" href="/">
            <span className="brand-mark" aria-hidden="true">
              A
            </span>
            <span>
              <strong>Agent Observatory</strong>
              <small>Harness runtime signals</small>
            </span>
          </Link>
          <div className="live-indicator">
            <span /> Ingestion online
          </div>
        </header>
        <main className="shell">{children}</main>
      </body>
    </html>
  );
}
