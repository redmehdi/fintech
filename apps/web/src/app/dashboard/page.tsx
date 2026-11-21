import type { Metadata } from "next";
import { KycDashboard } from "./KycDashboard";

export const metadata: Metadata = {
  title: "Dashboard (Simulation) · EUR–MAD Transfers",
};

export default function DashboardPage() {
  return (
    <main className="container">
      <h1>Dashboard</h1>
      <KycDashboard />
    </main>
  );
}
