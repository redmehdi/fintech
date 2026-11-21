import type { NextConfig } from "next";

/**
 * Server-side API origin for the same-origin proxy below. Browser code calls `/api/v1/...` on the web
 * origin, so the API needs no CORS allowlist. Not `NEXT_PUBLIC_*`: never exposed in the bundle.
 */
function apiOrigin(): string {
  const origin = new URL(process.env.EURMAD_API_ORIGIN ?? "http://localhost:8080");
  if (origin.protocol !== "https:" && origin.hostname !== "localhost" && origin.hostname !== "127.0.0.1") {
    throw new Error("EURMAD_API_ORIGIN must use https outside localhost");
  }
  return origin.origin;
}

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  async rewrites() {
    // Only the versioned API surface is proxied; nothing else on the API origin is reachable.
    return [{ source: "/api/v1/:path*", destination: `${apiOrigin()}/v1/:path*` }];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
