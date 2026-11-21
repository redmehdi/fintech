import Link from "next/link";

export default function HomePage() {
  return (
    <main className="container">
      <h1>EUR–MAD Transfers</h1>
      <p>
        This is a simulation-only prototype for EUR↔MAD transfer quotes and exchange-offer matching.
        Rates shown are illustrative. No funds are held, exchanged or paid out by this application.
      </p>
      <p>
        <Link href="/dashboard">Go to your dashboard</Link>
      </p>
    </main>
  );
}
