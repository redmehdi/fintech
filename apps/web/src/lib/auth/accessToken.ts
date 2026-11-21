/**
 * Source of the OIDC access token for API calls from the browser.
 *
 * Browser sign-in (OIDC authorization code + PKCE against the managed IdP) is not implemented yet,
 * so no token is available and authenticated screens render their signed-out state. Tokens must
 * never be read from URLs, build-time environment variables or other bundle-visible sources.
 */
export function getAccessToken(): string | undefined {
  return undefined;
}
