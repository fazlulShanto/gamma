# Security review — 31 October 2026

Reviewed `dev` at `a3562193d0fbe76cae6cd63ead9c96d7751f3ad4` and prepared fixes
on the `cyber-security` worktree branch. This is a source review with isolated
regression tests and dependency-advisory checks, not a penetration test of a
deployed Gamma instance. No production accounts, libraries, or running developer
services were used. Severity below describes the application impact and its
stated prerequisites; dependency scanner severities are reported separately.

## Confirmed application findings

| Finding | Severity | Prerequisite | Status |
|---|---|---|---|
| Share URLs bypassed integration-token write/workspace restrictions | High | A read token and an existing same-workspace share; another workspace's restricted link for cross-workspace access | Fixed |
| Raw note HTML could execute same-origin JavaScript | High | A victim renders a malicious shared/imported note | Fixed |
| A remote workspace role could inject HTML into privileged desktop chrome | High | The desktop app connects to an attacker-controlled or compromised Gamma server | Fixed |
| Recovery left pending account-change credentials usable | High | A pending email-change link created before recovery, or an old-inbox reset link | Fixed |
| An unverified account could attach a persistent attacker sign-in identity | High | Ability to register the victim's address before its owner and attach the attacker's provider account | Fixed |
| Open collaboration sockets survived browser-session revocation | Medium | An already-open authenticated page socket | Fixed |
| Outbound URL checks could be bypassed by changing DNS answers | High | Permission to trigger a guarded URL fetch and control of that hostname's DNS | Fixed |

### Integration-token scope through shares

`share_grant()` and `require_ws_writer()` accepted share access computed from
the token owner's account without preserving the token's narrower authority.
An owner's **read** token could read an existing restricted/view share's token
through `/api/share-settings/{page}`, then append `?share=...` to a page-operation
request. The server treated it as the owner and accepted a content insertion.
With a known restricted share for a second workspace, the same account identity
also allowed a token confined to the first workspace to read the second.

The shared authorization helper now intersects share access with the token's
workspace, current membership, and write scope. The direct share resolver uses
the same helper. Browser-session and anonymous share behavior is preserved.

- Code: [auth.py](../../backend/gamma/auth.py),
  [share routes](../../backend/gamma/routers/shares.py).
- Proof/regression: [test_token_share_scope.py](../../backend/tests/test_token_share_scope.py).
  Three cases failed against the original code; the tests also preserve valid
  same-workspace write-token access.
- Current contract: [MCP and integration tokens](../dev/mcp.md).

### Stored script execution in note rendering

The note renderer enabled `rehypeRaw` without sanitizing its HTML syntax tree.
An HTML `iframe` with a `srcdoc` document survived the actual ReactMarkdown
pipeline. Its document could execute scripts with the Gamma page's origin,
allowing authenticated API actions and data reads as the viewing user. React's
handling of inline event attributes does not prevent this embedded-document path.

The renderer now applies `rehype-sanitize` after raw HTML parsing and before
KaTeX rendering. Its schema strips active embedded documents, unsafe URLs and
arbitrary CSS while retaining the editor's supported callouts, links, math,
diagram metadata, and restricted color spans.

- Code: [BlockTree.jsx](../../frontend/src/editor/BlockTree.jsx),
  [noteSanitize.js](../../frontend/src/editor/noteSanitize.js).
- Regressions: [noteSanitize.test.mjs](../../frontend/tests/noteSanitize.test.mjs),
  [browser reproduction](../../frontend/tests/e2e/markdownSecurity.mjs).
- The upstream ReactMarkdown project specifically calls out the need to
  sanitize when plugins allow unsafe HTML. See its
  [security guidance](https://github.com/remarkjs/react-markdown#security).

### Remote data in privileged desktop chrome

`desktop/ui/bar.html` interpolated the workspace `role` returned by a remote
server into `innerHTML`. That bar has the privileged `gammaShell` bridge; code
in it can request the server registry and privileged server/settings operations.
The role was rendered as part of normal state updates, so opening the menu was
not necessary. This is a remote-server-to-desktop-privilege crossing; no claim of
operating-system code execution is needed to establish its impact.

The role now uses `textContent`. Related hardening restricts shell IPC to the
actual top-level bar/launcher views and their exact bundled files. An arbitrary
`file:` URL or subframe is no longer sufficient to be treated as trusted chrome.

- Code: [bar.html](../../desktop/ui/bar.html),
  [shellSecurity.js](../../desktop/lib/shellSecurity.js),
  [main.js](../../desktop/main.js), [preload.js](../../desktop/preload.js).
- Reference: Electron recommends validating every IPC sender and limiting
  navigation in its [security checklist](https://www.electronjs.org/docs/latest/tutorial/security).
- Separate outstanding concern: the locked Electron runtime has its own
  advisories; the application fix is not a substitute for upgrading it.

### Recovery links retained authority after account recovery

A registrant could create a pending email-change link to an address they control.
If the real inbox owner then recovered the account through password reset or
an authoritative external-provider sign-in, that pending link remained valid.
The attacker could redeem it and change the recovered account's email. Reset
links delivered to an old email address also remained valid after an email change.

Credential recovery and revoke-all now invalidate pending email links, pending
server connects and provider-link flows as well as sessions and OAuth grants.
Changing email invalidates links delivered under the previous address. Token
consumption and sensitive checks use write transactions to prevent issuance or
confirmation from racing recovery.

### Unverified account provider-link persistence

An attacker who registered the victim's email could attach the attacker's own
Google/GitHub identity before proving ownership of the victim's inbox. That
identity survived recovery by the real owner, creating another way back into the
account. Registration may require an invitation depending on server settings;
this does not require access to the victim's mailbox.

Attaching a provider now requires a verified account email. Recovery of an
unverified account removes identities attached before inbox ownership was
established. This also handles legacy unverified rows created before the fix.

- Code for both recovery findings:
  [accounts.py](../../cloud/gammacloud/accounts.py),
  [identities.py](../../cloud/gammacloud/identities.py),
  [account routes](../../cloud/gammacloud/routers/accounts.py),
  [external sign-in routes](../../cloud/gammacloud/routers/external.py).
- Proof/regression: [test_recovery_security.py](../../cloud/tests/test_recovery_security.py)
  contains eight cases that failed before the fix.
- Deployment follow-up: already-verified accounts with previously attached
  malicious identities cannot be identified automatically from these records.
  Review suspicious linked providers and revoke affected credentials. The patch
  does not establish that any account was exploited.

### Session revocation and collaboration sockets

The page socket checked its browser cookie at connection time but retained only
the account identity for subsequent access decisions. Logout, a password reset,
session deletion, or expiry therefore did not prevent an existing connection
from receiving later private edits.

Peers now retain the admission session privately. Incoming traffic and outgoing
broadcasts validate the session off the event loop; revoked peers are removed
and closed. Anonymous share visitors continue to use their independent share
authority. Validation occurs at message boundaries, not on a periodic timer.

- Code: [collab.py](../../backend/gamma/collab.py),
  [socket routes](../../backend/gamma/routers/collab.py).
- Proof/regression:
  [test_collab_session_revocation.py](../../backend/tests/test_collab_session_revocation.py).
  Four original-code cases received private edits after revocation/expiry.

### DNS rebinding in guarded fetches

`validate_public_url()` checked one DNS resolution, then urllib's transport
resolved the hostname again. An attacker-controlled hostname could answer with
a public address for validation and a private/loopback address when connecting.
Checking redirect targets alone did not close this gap. The previous address
predicate also permitted non-global shared address space such as `100.64.0.0/10`.

The HTTP(S) transport now validates the complete DNS answer and connects directly
to a checked numeric socket address. It preserves the original HTTP Host and
TLS SNI/certificate name. Redirects remain checked, and non-global addresses are
rejected. Malformed hosts/ports become normal blocked-URL errors.

**Compatibility change:** guarded fetches now bypass environment HTTP(S) proxies,
because the application's checks cannot constrain a proxy's independent DNS
resolution. Deployments requiring an outbound proxy need a separately designed
trusted proxy policy. An egress firewall is still useful protection against
network routing/proxy behavior outside the application.

- Code: [net_guard.py](../../backend/gamma/net_guard.py).
- Regressions: [test_net_guard.py](../../backend/tests/test_net_guard.py) simulates
  rebinding, mixed public/private answers, shared address space, HTTPS hostname
  preservation, redirects and environment proxies without contacting internal hosts.
- Reference: [OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

## Dependency findings requiring follow-up

The advisory snapshot was taken on 31 October 2026 using `npm audit --json
--package-lock-only --ignore-scripts` in `frontend`, `desktop`, and `sites`, plus
OSV queries for Python requirement pins and installed transitive dependencies.
These are affected-package matches, **not counts of demonstrated Gamma exploits**.
Transitive packages and multiple paths can inflate the npm counts.

| Lockfile | Reported packages | Critical | High | Moderate | Low |
|---|---:|---:|---:|---:|---:|
| Frontend | 38 | 3 | 22 | 6 | 7 |
| Desktop | 23 | 0 | 8 | 15 | 0 |
| Website tooling | 4 | 0 | 4 | 0 | 0 |

The Python query covered 57 package/version entries, including installed
transitives. Python transitive dependencies are not locked, so this is not a
complete reproducible inventory of a freshly built container. The same certifi
release appears with two equivalent version spellings in the query.

Prioritize these upgrades and validate the relevant consumers:

1. **Electron 38.8.6:** the desktop actually exposes Promise-returning bridge
   calls to remote content, matching the prerequisite of
   [GHSA-h7rp-cf8h-j98x](https://github.com/electron/electron/security/advisories/GHSA-h7rp-cf8h-j98x).
   That advisory requires a patched Electron release; application checks alone
   cannot repair the runtime. Move to a currently supported patched line, then
   exercise packaged Windows/macOS/Linux builds, navigation, IPC, permissions,
   updates and sign-in. Other runtime advisories also match this old version.
2. **Pillow 12.3.0:** OSV reports image-decoder/encoder memory-safety and resource
   exhaustion advisories fixed in **13.0.0**. Gamma processes uploaded images and
   PDF image content; this is a high-priority native-parser upgrade, with image
   import/export and packaging regression tests. Example:
   [FPX decoder advisory](https://github.com/advisories/GHSA-5x8w-8jj5-q52h).
3. **python-multipart 0.0.26** in backend and cloud: advance to at least
   **0.0.31** and test form/OAuth/upload handling. The matched records include
   [unbounded part headers](https://github.com/advisories/GHSA-pp6c-gr5w-3c5g)
   and query-string/parameter parsing issues.
4. **PyPDF2 3.0.1:** an untrusted-PDF parser
   [infinite-loop advisory](https://github.com/advisories/GHSA-4vvm-4w3v-6mr8)
   has no patched PyPDF2 release. Migrate to a maintained `pypdf` version and
   validate Gamma's many annotation/export consumers. A version-only change
   cannot perform that migration.
5. **certifi 2026.07.22:** advance to **2026.10.18** or a later vetted release
   to remove the affected trust roots; see
   [GHSA-6q48-6c7w-9gw2](https://github.com/advisories/GHSA-6q48-6c7w-9gw2).
6. **PyJWT 2.14.0** in cloud: move to at least **2.15.0**, with provider/OIDC
   tests. The matched records cover unverified-payload parsing and inconsistent
   OKP keys; see [GHSA-42vr-xj54-vc7v](https://github.com/advisories/GHSA-42vr-xj54-vc7v).
7. **Vite 5.4.21:** upgrade the development toolchain to a supported patched
   release. Exposure depends on how the dev server is bound and reached;
   production static assets are not a Vite development server. A relevant
   [Windows file-deny bypass](https://github.com/vitejs/vite/security/advisories/GHSA-fx2h-pf6j-xcff)
   requires network exposure. Do not expose the development server publicly.
8. **DOMPurify 3.4.16**, **KaTeX 0.16.47**, and transitive npm packages:
   update compatibly and rerun diagram/math tests. DOMPurify's critical
   [template/shadow-root advisory](https://github.com/cure53/DOMPurify/security/advisories/GHSA-cg2g-xh64-v4q7)
   requires `SAFE_FOR_TEMPLATES` plus specific DOM modes; Gamma's explicit SVG
   sanitizer does not enable that template option. This scan result therefore
   does not prove a second stored-XSS vulnerability in that SVG path. Its
   patched version is 3.4.17. Development/build-only packages also account for
   many alerts, including Babel, PostCSS and website Wrangler dependencies.

These dependency upgrades are **not implemented in this branch**. Its only new
dependency is `rehype-sanitize` for the confirmed note-rendering flaw. The
branch should not be described as clearing the dependency audit or making the
entire product secure.

## Deployment and coverage limits

- Fleet containers receive shared S3 credentials with different object prefixes.
  A prefix in application configuration is not an IAM isolation boundary.
  Prefer per-tenant credentials/policies if containers are expected to isolate
  mutually untrusted tenants. This is a conditional blast-radius concern,
  not a demonstrated unauthenticated credential leak.
- Forwarded-client-IP trust depends on preventing direct access around the
  trusted reverse proxy. Verify firewall/network rules in the actual deployment;
  source review cannot confirm them.
- Desktop permission handling in `desktop/lib/microphone.js` broadly grants
  requests outside its microphone-specific checks. Review the intended
  per-origin permission policy separately; no additional exploit was reproduced
  here. Extension inspection was static, without runtime-flow validation.
- The targeted source scan found no matching private-key, AWS-access-key,
  GitHub-token, or live Stripe-key patterns in the inspected application/docs/
  workflow paths. This was not an exhaustive secret scan, and did not inspect
  Git history, ignored data, production logs or deployment secrets.
- Native iPad code, a full CI supply-chain audit, external-provider configuration,
  deployed CSP/TLS headers, and container/OS package inventories were not fully
  assessed. Existing defenses such as workspace helpers, upload filename checks,
  sandboxed uploaded content and archive guards are useful but are not a proof
  that all attack paths are covered.

## Validation

- Outbound-fetch/security consumers: **115 passed** across `test_net_guard`,
  `test_security_access`, `test_pdf_proxy`, `test_publisher_sessions`,
  `test_fetch_handoff`, and `test_paper_discovery`.
- Token/share/session boundaries: **140 distinct tests passed** across token,
  sharing, collaboration, comments, inbox and upload-scope suites. A broader
  write-access test selection encountered an existing unstubbed `models.dev`
  request blocked by the sandbox; that selection is not claimed as passed.
- Cloud: **390 tests passed** in the full suite. An existing Turnstile error-path
  test now mocks the network failure instead of connecting to a loopback port;
  this keeps validation independent of sandbox networking.
- Client: **16 frontend module tests**, **6 desktop main tests**, and **87
  browser steps** across notes/PDF/sharing/diagrams passed. The production
  frontend build and standalone Chromium/Electron security regressions also
  passed. The browser regression first demonstrates the old note-rendering
  flaw before checking the fixed renderer. The Electron check uses the
  installed runtime and a disposable profile; it does not validate a newer
  Electron version or a packaged release.

Application tests do not substitute for the outstanding dependency migrations
and packaging checks.

After deploying these application fixes, restart affected backend/cloud
processes to close connections created under the old socket behavior, rebuild
the frontend/desktop artifacts, and review any suspicious existing linked
identities. This review does not deploy these changes.
