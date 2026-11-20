import type { Metadata } from "next";
import type { ReactNode } from "react";
import { operatingMode } from "@/lib/operatingMode";
import "./globals.css";

export const metadata: Metadata = {
  title: "EUR–MAD Transfers (Simulation)",
  description: "Simulation-only EUR↔MAD transfer and exchange-offer prototype. No real money moves.",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en" dir="ltr">
      <body>
        <div className="simulation-banner" role="status" data-operating-mode={operatingMode}>
          {operatingMode} MODE · No real money, FX or payouts are processed.
        </div>
        {children}
      </body>
    </html>
  );
}
