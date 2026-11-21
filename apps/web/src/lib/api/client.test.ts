import { describe, expect, it, vi } from "vitest";
import {
  Configuration,
  ProblemFromJSON,
  QuotesApi,
  ResponseError,
  type CreateQuoteRequest,
  type Problem,
} from "./generated";

const request = {
  idempotencyKey: "synthetic-quote-001",
  quoteRequest: {
    sourceCurrency: "MAD",
    targetCurrency: "EUR",
    sourceAmountMinor: "9007199254740993",
    purpose: "FAMILY_SUPPORT",
  },
} satisfies CreateQuoteRequest;

describe("generated API client", () => {
  it("sends the required idempotency key and preserves exact money and rate strings", async () => {
    const wireQuote = {
      id: "00000000-0000-0000-0000-000000000001",
      sourceCurrency: request.quoteRequest.sourceCurrency,
      targetCurrency: request.quoteRequest.targetCurrency,
      sourceAmountMinor: request.quoteRequest.sourceAmountMinor,
      targetAmountMinor: "839982480488424",
      feeSourceMinor: "23",
      totalDebitMinor: "9007199254741016",
      rate: "0.093256789012",
      rateSource: "SYNTHETIC",
      expiresAt: "2026-11-21T12:00:00Z",
      mode: "SIMULATION",
      status: "ISSUED",
    };
    const fetchApi = vi.fn<typeof fetch>().mockResolvedValue(Response.json(wireQuote, { status: 201 }));
    const client = new QuotesApi(new Configuration({ accessToken: "synthetic-access-token", fetchApi }));

    const quote = await client.createQuote(request);

    expect(fetchApi).toHaveBeenCalledExactlyOnceWith(
      "http://localhost:8080/v1/quotes",
      expect.objectContaining({
        method: "POST",
        headers: {
          Authorization: "Bearer synthetic-access-token",
          "Content-Type": "application/json",
          "Idempotency-Key": request.idempotencyKey,
        },
        body: expect.any(String),
      }),
    );
    const sentBody = fetchApi.mock.calls[0]?.[1]?.body;
    expect(JSON.parse(String(sentBody))).toEqual(request.quoteRequest);
    expect(quote).toMatchObject({
      sourceAmountMinor: wireQuote.sourceAmountMinor,
      targetAmountMinor: wireQuote.targetAmountMinor,
      feeSourceMinor: wireQuote.feeSourceMinor,
      totalDebitMinor: wireQuote.totalDebitMinor,
      rate: wireQuote.rate,
      mode: "SIMULATION",
    });
  });

  it("exposes a Problem response to callers without interpreting it as success", async () => {
    const problem = {
      code: "CONFLICT",
      message: "Conflict",
      correlationId: "00000000-0000-0000-0000-000000000001",
    } satisfies Problem;
    const fetchApi = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(problem, {
        status: 409,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );
    const client = new QuotesApi(new Configuration({ fetchApi }));

    const error: unknown = await client.createQuote(request).catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(ResponseError);
    if (!(error instanceof ResponseError)) throw new Error("Expected an HTTP error");
    expect(error.response.status).toBe(409);
    expect(ProblemFromJSON(await error.response.json())).toEqual(problem);
  });
});
