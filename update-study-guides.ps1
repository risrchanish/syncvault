Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$dir = "D:\syncvault\user-service"

$newCSS = '.dd-grid{padding:16px 24px 20px}.dd-card{background:#1c2128;border:1px solid var(--border);border-radius:8px;padding:16px 20px;margin-bottom:10px}.dd-card:last-child{margin-bottom:0}.dd-card h4{font-size:0.75rem;font-weight:700;text-transform:uppercase;letter-spacing:.5px;margin-bottom:10px}.dd-card p{font-size:0.82rem;line-height:1.7;color:var(--text);margin-bottom:6px}.dd-card ul{padding-left:18px;margin-top:4px}.dd-card li{font-size:0.82rem;line-height:1.65;color:var(--text);margin-bottom:5px}.dd-card code{background:#21262d;padding:1px 5px;border-radius:3px;font-family:Consolas,monospace;font-size:0.77rem;color:var(--orange)}.isection.why{border-top:2px solid var(--blue)}.isection.why .dd-card h4{color:var(--blue)}.isection.wrong{border-top:2px solid var(--red)}.isection.wrong .dd-card h4{color:var(--red)}.isection.improve{border-top:2px solid var(--green)}.isection.improve .dd-card h4{color:var(--green)}'

function Make-Sections($cls, $why, $wrong, $improve) {
    $s  = '<div class="isection why">'
    $s += '<h2>Why This Design Decision &mdash; ' + $cls + '</h2>'
    $s += '<div class="dd-grid"><div class="dd-card"><h4>Rationale</h4>' + $why + '</div></div></div>'
    $s += '<div class="isection wrong">'
    $s += '<h2>What Could Go Wrong &mdash; ' + $cls + '</h2>'
    $s += '<div class="dd-grid"><div class="dd-card"><h4>Risks &amp; Pitfalls</h4>' + $wrong + '</div></div></div>'
    $s += '<div class="isection improve">'
    $s += '<h2>How I Would Improve This &mdash; ' + $cls + '</h2>'
    $s += '<div class="dd-grid"><div class="dd-card"><h4>Improvements</h4>' + $improve + '</div></div></div>'
    return $s
}

function Insert-Before-Nav($content, $pageIndex, $sections) {
    $script:navCount = 0
    $result = [regex]::Replace($content, '(?m)^<div class="nav">', {
        param($m)
        $cur = $script:navCount; $script:navCount++
        if ($cur -eq $pageIndex) { return $sections + "`n" + $m.Value }
        return $m.Value
    })
    return $result
}

function Update-File($filename, $pages) {
    $path = Join-Path $dir $filename
    $content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    if (-not $content.Contains('.dd-grid{')) {
        $content = $content.Replace('</style>', $newCSS + '</style>')
    }
    $hldLink = '<a href="study-guide-00-hld.html">00 HLD</a>'
    $content = [regex]::Replace($content, '(<div class="pkg-links">)', '$1' + "`n      " + $hldLink)
    for ($p = 0; $p -lt $pages.Count; $p++) {
        $pg = $pages[$p]
        $sections = Make-Sections $pg[0] $pg[1] $pg[2] $pg[3]
        $content = Insert-Before-Nav $content $p $sections
    }
    [System.IO.File]::WriteAllText($path, $content, [System.Text.Encoding]::UTF8)
    Write-Host "Updated $filename ($($pages.Count) pages)"
}

# ---- FILE 01 ----
$w0='<p><code>@SpringBootApplication</code> bundles <code>@Configuration</code>, <code>@EnableAutoConfiguration</code>, and <code>@ComponentScan</code> in one annotation. Servlet-based (Tomcat) rather than reactive (WebFlux) because Spring Data JPA and Hibernate are blocking &mdash; WebFlux would require switching the entire persistence layer to R2DBC.</p>'
$b0='<p>Without <code>server.shutdown=graceful</code>, a Kubernetes SIGTERM kills the JVM mid-transaction, aborting in-flight login requests. The component scan covers all of <code>com.syncvault.userservice</code> &mdash; a misconfigured <code>@Component</code> in any subpackage silently registers an unwanted bean at startup.</p>'
$i0='<p>Add <code>server.shutdown=graceful</code> with <code>spring.lifecycle.timeout-per-shutdown-phase=30s</code> so rolling deploys drain connections first. Add an <code>ApplicationReadyEvent</code> listener that logs the active Spring profile, JWT expiry value, and database URL on startup &mdash; invaluable for catching wrong-environment deployments before the first request.</p>'

$w1='<p>The OpenAPI 3 spec is generated at runtime from actual code, eliminating documentation drift. The <code>SecurityScheme</code> named <code>BearerAuth</code> is defined once here; controllers use <code>@SecurityRequirement(name = "BearerAuth")</code> to mark secured endpoints. The "Authorize" button in Swagger UI reads this scheme to pre-fill JWT tokens.</p>'
$b1='<p>Swagger UI is publicly accessible in production by default, exposing your full API surface to attackers for reconnaissance. If the scheme name <code>BearerAuth</code> here does not exactly match the <code>@SecurityRequirement</code> annotation on a controller, that endpoint shows no lock icon &mdash; developers assume it is open when it is not.</p>'
$i1='<p>Gate the bean with <code>@Profile("!prod")</code> so Swagger UI only registers in dev and staging. Add <code>openapi-generator-maven-plugin</code> bound to <code>generate-sources</code> to auto-generate a typed Java/TypeScript client SDK from the spec &mdash; eliminates hand-written HTTP calls in other services or the frontend.</p>'

$w2='<p><code>SessionCreationPolicy.STATELESS</code> ensures no HTTP session is ever created, enabling horizontal scaling without sticky sessions. CSRF is disabled correctly because JWT in the <code>Authorization</code> header (unlike cookies) cannot be sent cross-origin without explicit client cooperation. BCrypt cost 12 gives ~300ms per hash &mdash; expensive for brute-force, imperceptible for the user.</p>'
$b2='<p><code>/actuator/**</code> is fully public &mdash; in production this exposes environment variables (including the JWT secret), heap dumps, and bean wiring to anyone on the internet. No CORS configuration means browser-based frontends on a different origin are silently blocked, causing confusing auth failures with no useful error message.</p>'
$i2='<p>Add a <code>CorsConfigurationSource</code> bean with explicit allowed origins. Move actuator behind <code>management.server.port=9090</code> (a port not exposed by the load balancer) or protect it with HTTP Basic. Add security response headers &mdash; <code>X-Frame-Options</code>, <code>X-Content-Type-Options</code>, <code>Strict-Transport-Security</code> &mdash; via <code>http.headers()</code>.</p>'

Update-File 'study-guide-01-root-config.html' @(
    @('UserServiceApplication', $w0, $b0, $i0),
    @('OpenApiConfig',          $w1, $b1, $i1),
    @('SecurityConfig',         $w2, $b2, $i2)
)

# ---- FILE 02 ----
$w0='<p>UUID primary keys (generated by PostgreSQL <code>gen_random_uuid()</code>) prevent enumeration attacks &mdash; sequential integer IDs reveal how many users exist and allow guessing adjacent IDs. <code>@Builder</code> prevents telescoping constructors. <code>subscription_plan</code> and <code>storage_used_bytes</code> live in the User entity because every file operation checks quota, avoiding a separate service roundtrip.</p>'
$b0='<p><code>subscriptionPlan</code> is stored as raw <code>VARCHAR</code> &mdash; a typo like <code>"PREMIUMS"</code> persists silently and breaks any downstream switch statement. Without <code>@Version</code> for optimistic locking, two concurrent profile updates produce a lost update (last write wins). <code>storageUsedBytes</code> updated without a DB-level atomic increment can desync under concurrent multi-device uploads.</p>'
$i0='<p>Change <code>subscriptionPlan</code> to a Java <code>enum</code> with <code>@Enumerated(EnumType.STRING)</code> for compile-time safety. Add <code>@Version Long version</code> for optimistic locking. Replace read-modify-write on <code>storageUsedBytes</code> with a SQL atomic increment &mdash; <code>UPDATE users SET storage_used_bytes = storage_used_bytes + ?</code> &mdash; to prevent drift under concurrent uploads.</p>'

$w1='<p>Storing only the SHA-256 hash of the refresh token means a database breach is useless &mdash; the attacker needs the original random UUID (122 bits of entropy) to compute the same hash. A separate table keeps the <code>users</code> table lean (queried on every authenticated request) while <code>refresh_tokens</code> is only queried on refresh. Soft revocation creates an audit trail and enables theft detection.</p>'
$b1='<p>Without a scheduled cleanup job, revoked and expired rows accumulate indefinitely; the table can reach millions of rows, degrading index scan performance. Using <code>LocalDateTime</code> instead of <code>Instant</code> is timezone-sensitive: if the JVM timezone changes on container restart, <code>expiresAt</code> comparisons silently break.</p>'
$i1='<p>Add a <code>@Scheduled(cron = "0 0 3 * * *")</code> job that bulk-deletes rows <code>WHERE expires_at &lt; NOW() OR is_revoked = true</code> nightly. Switch <code>expiresAt</code> and <code>createdAt</code> to <code>Instant</code> (mapped to <code>TIMESTAMP WITH TIME ZONE</code> in PostgreSQL). Add a partial index on <code>(token_hash) WHERE is_revoked = false</code> to reduce index size and speed up the hot-path lookup.</p>'

Update-File 'study-guide-02-entity.html' @(
    @('User',         $w0, $b0, $i0),
    @('RefreshToken', $w1, $b1, $i1)
)

# ---- FILE 03 ----
$w0='<p>Spring Data JPA derives full JPQL from method names at startup &mdash; <code>existsByEmail</code> and <code>findByEmail</code> generate <code>SELECT ... WHERE email = ?</code> without hand-written SQL. This eliminates query typos and fails fast at startup if a method name is invalid. The interface-based approach lets Spring inject a CGLIB proxy with all persistence logic.</p>'
$b0='<p><code>existsByEmail()</code> followed by <code>save()</code> in <code>AuthService</code> is a classic TOCTOU race &mdash; two concurrent registration requests can both pass the check before either inserts, hitting the DB UNIQUE constraint and throwing <code>DataIntegrityViolationException</code> instead of the expected <code>EmailAlreadyExistsException</code>. Without query hints, <code>findByEmail</code> hits the database on every call.</p>'
$i0='<p>Remove the <code>existsByEmail()</code> pre-check; instead catch <code>DataIntegrityViolationException</code> from <code>save()</code> and re-throw as <code>EmailAlreadyExistsException</code> &mdash; race-condition-free and semantically correct. Add <code>@Cacheable("users")</code> with a short TTL on <code>findByEmail</code> to reduce repeated database hits within the same session.</p>'

$w1='<p><code>findByTokenHash</code> is the hot path &mdash; called on every <code>/auth/refresh</code> request. The database index on <code>token_hash</code> (created in the V1 migration) ensures O(log n) lookup even with millions of rows. <code>deleteByExpiresAtBeforeAndRevokedTrue</code> is type-safe and refactor-friendly: renaming the field in <code>RefreshToken</code> causes a compile error here rather than a silent runtime SQL failure.</p>'
$b1='<p>The derived <code>deleteBy...</code> method loads all matching entities into memory before deleting them in older Spring Data JPA versions &mdash; on a large table this risks OOM. <code>findByTokenHash</code> runs without caching; at peak traffic every token refresh generates a database roundtrip, amplifying load during thundering-herd token refresh storms.</p>'
$i1='<p>Replace <code>deleteBy...</code> with a <code>@Modifying @Query("DELETE FROM RefreshToken r WHERE r.expiresAt &lt; :cutoff AND r.revoked = true")</code> for bulk in-database delete with no entity loading. Add Redis caching on <code>findByTokenHash</code> with TTL equal to the access token lifetime to absorb thundering-herd spikes without any DB load.</p>'

Update-File 'study-guide-03-repository.html' @(
    @('UserRepository',         $w0, $b0, $i0),
    @('RefreshTokenRepository', $w1, $b1, $i1)
)

# ---- FILE 04 ----
$p04 = @(
    @('LoginRequest',
      '<p>DTOs decouple the API wire format from the domain model &mdash; the <code>User</code> entity can gain new fields without changing the login contract. <code>@Valid</code> on the controller parameter triggers Bean Validation before the method body executes, keeping the service layer free of null-checks and type assertions.</p>',
      '<p>No rate limiting at the controller or filter level means an attacker can submit thousands of requests per second in a credential-stuffing attack. The <code>@NotBlank</code> constraint accepts any non-blank string &mdash; <code>"notanemail"</code> passes DTO validation and only fails at the database lookup, wasting a DB roundtrip.</p>',
      '<p>Add <code>@Email(message = "Invalid email format")</code> to the email field. Integrate Bucket4j to limit to 5 login attempts per IP per minute, returning <code>429 Too Many Requests</code> with a <code>Retry-After</code> header. Log failed attempts with the IP address and timestamp for security monitoring and alerting.</p>'
    ),
    @('LogoutRequest',
      '<p>Logout revokes the refresh token server-side, so the client must provide it. Accepting the token in the request body (not the <code>Authorization</code> header) means the client can log out even if the access token has already expired &mdash; important for "log out all devices" flows. A dedicated DTO makes the API contract explicit.</p>',
      '<p>After logout, the access token remains valid until its 15-minute expiry &mdash; there is no access token blacklist. An attacker who steals an access token can use it for up to 15 minutes after the legitimate user logs out. <code>@NotBlank</code> accepts any non-blank string, so a completely malformed value passes DTO validation.</p>',
      '<p>Add an access token blacklist in Redis: on logout, hash the access token and store it with TTL equal to its remaining lifetime, then check the blacklist in <code>JwtAuthenticationFilter</code>. Alternatively, reduce the access token lifetime from 15 minutes to 5 minutes to shrink the post-logout vulnerability window.</p>'
    ),
    @('RefreshTokenRequest',
      '<p>Token refresh is a dedicated operation &mdash; its own DTO prevents conflating it with the login flow. The client can rotate tokens silently in the background without re-entering credentials, enabling seamless session extension for long-lived sessions.</p>',
      '<p>If a client stores the refresh token in <code>localStorage</code> instead of an <code>HttpOnly</code> cookie, any injected XSS script can read and exfiltrate it. <code>@NotBlank</code> accepts any non-blank string, so a malformed token passes DTO validation and only fails when the hash lookup returns empty.</p>',
      '<p>Document in the API spec that refresh tokens must be stored in <code>HttpOnly Secure SameSite=Strict</code> cookies. Consider returning the new refresh token as a <code>Set-Cookie</code> response header rather than in the JSON body &mdash; JavaScript cannot read it, eliminating XSS-based refresh token theft.</p>'
    ),
    @('RegisterRequest',
      '<p>Validation annotations enforce business rules at the API boundary &mdash; password length and non-blank fields are checked before <code>AuthService</code> is ever called. A dedicated <code>RegisterRequest</code> (not reusing <code>LoginRequest</code>) means the registration contract evolves independently, e.g. adding a <code>fullName</code> field here does not affect the login DTO.</p>',
      '<p>No password complexity validation &mdash; <code>"aaaaaaaaaaaaaa"</code> (14 chars) passes <code>@Size(min=8)</code>. Email is not normalised to lowercase &mdash; <code>"User@Example.com"</code> and <code>"user@example.com"</code> are treated as different accounts, causing confusion. No phone or locale field now means a breaking API change later when they are needed.</p>',
      '<p>Add a custom <code>@PasswordStrength</code> validator requiring at least one uppercase letter, one digit, and one special character. Normalise email to <code>email.trim().toLowerCase()</code> in <code>AuthService.register()</code> before persisting. Add optional <code>phone</code> and <code>locale</code> fields now (null permitted) to avoid a v2 breaking change later.</p>'
    ),
    @('UpdateProfileRequest',
      '<p>A separate DTO from <code>RegisterRequest</code> enforces minimal surface area &mdash; users can update their name but not their email or password through this endpoint; those require separate verification flows. Exposing only what is updatable prevents accidentally updating sensitive fields through a single generic update endpoint.</p>',
      '<p><code>fullName</code> is the only updatable field &mdash; adding a new field like <code>avatarUrl</code> requires changing the DTO, controller, and service in lockstep. Without <code>@Size(max=100)</code>, a 200-character name violates the <code>VARCHAR(100)</code> database column constraint and surfaces as a cryptic Hibernate exception rather than a clean 400 validation error.</p>',
      '<p>Add <code>@Size(max=100)</code> to match the database column constraint so validation fails cleanly at the DTO boundary. Consider PATCH semantics with <code>JsonMergePatch</code> for flexible partial updates &mdash; allows adding new updatable fields without changing the endpoint signature or breaking existing clients.</p>'
    ),
    @('AuthResponse',
      '<p>Wrapping access token, refresh token, and <code>expiresIn</code> in a structured response gives clients everything they need without parsing the JWT themselves. The <code>expiresIn</code> field (seconds) lets clients schedule a silent refresh before expiry &mdash; without it, clients would have to decode the JWT <code>exp</code> claim themselves, coupling them to the token format.</p>',
      '<p>If server-side response logging is enabled (common in dev tools or API gateways), the refresh token appears in plain text in log files &mdash; a serious security leak. Returning both tokens in the JSON body means any XSS vulnerability can steal the refresh token from the response or from JavaScript-accessible storage.</p>',
      '<p>Return only the access token in the JSON body. Deliver the refresh token as an <code>HttpOnly Secure SameSite=Strict Set-Cookie</code> response header &mdash; JavaScript cannot read it, eliminating XSS-based refresh token theft. Ensure all response logging redacts token fields before writing to log storage.</p>'
    ),
    @('MessageResponse',
      '<p>A wrapper record ensures the response is always valid JSON &mdash; a bare <code>String</code> return is not a JSON object and causes parsing errors in strict clients. Reusing <code>MessageResponse</code> across endpoints (logout success, password reset confirmation) keeps the API consistent and reduces boilerplate DTOs.</p>',
      '<p>No <code>errorCode</code> field &mdash; clients can only distinguish errors by comparing the message string, which breaks when the API is internationalised (the text changes language but the code does not). Using the same class for both success and error responses without a <code>status</code> field creates ambiguity.</p>',
      '<p>Add an optional <code>errorCode</code> field (e.g. <code>USER_NOT_FOUND</code>, <code>TOKEN_INVALID</code>) that clients use for programmatic handling, leaving <code>message</code> for human display. This decouples client logic from message text and enables i18n without changing the client code.</p>'
    ),
    @('RefreshResponse',
      '<p>Returns both the new access token and new refresh token after rotation in one roundtrip &mdash; the client can atomically replace both stored tokens. The immutable record design (no setters) prevents accidental mutation before serialisation, ensuring the tokens returned to the client are exactly what was issued.</p>',
      '<p>If the client successfully calls <code>/auth/refresh</code> but then crashes before persisting the new tokens, the old token is already revoked &mdash; the user is silently logged out with no recovery path except re-entering credentials. There is no grace period on the revoked token to handle this crash-after-refresh scenario.</p>',
      '<p>Consider a 30-second grace period: keep the old token valid for one additional use after rotation. Store a <code>replacedBy</code> pointer (new token hash) on the revoked token &mdash; if the old token is presented again within 30 seconds, accept it and return the new tokens. This handles the crash-after-refresh scenario without meaningful security risk.</p>'
    ),
    @('RegisterResponse',
      '<p>Returning the <code>userId</code> immediately after registration gives the client an identifier for subsequent requests without a separate login call. Using a simple record keeps the response stable and serialisation straightforward. The success message provides a human-readable confirmation for display in the UI.</p>',
      '<p>Returning the <code>userId</code> on registration could aid user enumeration &mdash; an attacker can distinguish a successful registration from a duplicate-email failure by checking whether a <code>userId</code> is present. The client must call <code>/auth/login</code> separately after registering &mdash; two round trips for every new user.</p>',
      '<p>Return access and refresh tokens directly in the registration response (combined register + auto-login) to eliminate the extra roundtrip and improve new-user UX &mdash; users are immediately authenticated after registering. Or return only a generic success message (no <code>userId</code>) to prevent registration-based enumeration.</p>'
    ),
    @('UserProfileResponse',
      '<p><code>@Builder</code> enables fluent, readable construction in <code>UserService.toResponse()</code> and in test assertions. Exposing only non-sensitive fields (no <code>passwordHash</code>, no token data) enforces least exposure. <code>storageUsed</code> in raw bytes gives clients maximum flexibility for display formatting.</p>',
      '<p><code>storageUsed</code> is a raw <code>Long</code> (bytes) &mdash; every platform (web, iOS, Android) must independently implement conversion to "1.2 GB", risking inconsistent display. The response does not include the storage limit for the user''s plan, so clients cannot render a quota progress bar without a second API call.</p>',
      '<p>Add <code>storageLimit</code> (bytes, derived from subscription plan) and a computed <code>storagePercent</code> to the response so clients can render quota bars in one call. Add <code>subscriptionExpiry</code> for PREMIUM users so the client can warn about upcoming expiry without a separate billing endpoint call.</p>'
    )
)
Update-File 'study-guide-04-dto.html' $p04

# ---- FILE 05 ----
$p05 = @(
    @('EmailAlreadyExistsException',
      '<p>A specific exception type lets <code>GlobalExceptionHandler</code> map it to exactly HTTP 409 Conflict without catching broad <code>RuntimeException</code>s. The named domain exception makes the intent clear at the service layer &mdash; reading <code>AuthService</code>, you immediately understand this error means email collision, not a generic DB failure. Extending <code>RuntimeException</code> (unchecked) keeps service method signatures clean.</p>',
      '<p>The exception message includes the actual email address: <code>"Email already registered: user@example.com"</code> &mdash; this enables user enumeration. An attacker submitting random emails can confirm which ones are registered by checking for a 409 response. The email leaks through to the client via <code>GlobalExceptionHandler</code>.</p>',
      '<p>Remove the email from the error message &mdash; return a generic <code>"An account with this email already exists."</code> and log the email server-side with PII redaction for GDPR compliance. For maximum security, return the same <code>"Registration failed"</code> response regardless of whether the failure was a duplicate email or any other error, eliminating the timing and message-based enumeration attack surface.</p>'
    ),
    @('InvalidTokenException',
      '<p>A specific <code>InvalidTokenException</code> allows <code>GlobalExceptionHandler</code> to return exactly HTTP 401 Unauthorized &mdash; the correct status code for an invalid authentication credential. Throwing it from <code>AuthService.refresh()</code> and <code>logout()</code> makes the contract explicit: callers know this operation can fail with an auth error, not a generic runtime error.</p>',
      '<p>The message <code>"Invalid refresh token"</code> tells an attacker that a refresh token was expected in this endpoint, disclosing the authentication flow structure. A timing difference between code paths for "token not found" and "token expired" could leak information about token validity through response time differences.</p>',
      '<p>Return a generic <code>"Authentication failed"</code> message in all invalid-token cases &mdash; never distinguish "invalid", "expired", or "revoked" in the response body (these distinctions belong in server logs only). Use constant-time token hash lookup to prevent timing-based information leakage.</p>'
    ),
    @('UserNotFoundException',
      '<p>Returns HTTP 404 Not Found when a valid JWT references a user deleted from the database &mdash; distinguishing this from 401 Unauthorized (bad token) and 403 Forbidden (valid token, no permission). Being specific about the failure type enables correct client-side handling: 404 means "retry will not help", 401 means "re-authenticate".</p>',
      '<p>Returning 404 for a deleted user vs 401 for an invalid token helps an attacker distinguish "account deleted" from "token forged" &mdash; an information leak. If this exception propagates from <code>JwtAuthenticationFilter</code> via <code>UserDetailsServiceImpl</code>, Spring''s default error handler returns an unformatted response, bypassing <code>GlobalExceptionHandler</code>.</p>',
      '<p>For auth-critical paths (JWT filter), catch <code>UserNotFoundException</code> in the filter and return 401 &mdash; never let it surface as 404 during authentication. Implement soft-delete on the <code>User</code> entity (<code>is_active = false</code>) rather than hard deletes &mdash; existing JWTs stay valid until expiry and can return a meaningful "account deactivated" response.</p>'
    ),
    @('GlobalExceptionHandler',
      '<p><code>@RestControllerAdvice</code> centralises all error handling &mdash; no try-catch duplication across controllers. <code>@ExceptionHandler</code> methods return a consistent JSON structure for all exception types, simplifying frontend error handling. Spring auto-discovers the class through component scanning without any explicit registration.</p>',
      '<p>Without a catch-all <code>@ExceptionHandler(Exception.class)</code>, any unhandled exception produces Spring''s default error response at <code>/error</code>, which can include a stack trace when <code>server.error.include-stacktrace=always</code> is set. The order of <code>@ExceptionHandler</code> methods matters &mdash; a broader type placed above a specific one catches it first, causing incorrect status codes.</p>',
      '<p>Add a catch-all <code>@ExceptionHandler(Exception.class)</code> that logs the full stack trace with a correlation ID, but returns only <code>"An unexpected error occurred [id: {correlationId}]"</code> to the client. Include <code>errorCode</code>, <code>timestamp</code>, and <code>path</code> in every error response &mdash; enables client-side i18n and log correlation without exposing internals.</p>'
    )
)
Update-File 'study-guide-05-exception.html' $p05

# ---- FILE 06 ----
$p06 = @(
    @('JwtTokenProvider',
      '<p>Symmetric HMAC-SHA256 signing is sufficient when the same service both issues and validates tokens &mdash; asymmetric RS256 is needed only when other microservices must verify tokens without sharing the private key. <code>Keys.hmacShaKeyFor()</code> validates key length at startup (minimum 256 bits), catching misconfiguration before the first request is processed and preventing silent algorithm downgrade.</p>',
      '<p>The signing secret is plain text in <code>application.yaml</code> &mdash; committing this file to version control permanently exposes the secret (git history is forever). A single compromised symmetric secret invalidates all outstanding tokens simultaneously. There is no key rotation mechanism: changing the secret logs out every user at once.</p>',
      '<p>Load <code>jwt.secret</code> from an environment variable or secrets manager (HashiCorp Vault, AWS Secrets Manager) &mdash; never hardcode in YAML. Switch to RS256 with a private/public key pair: User Service signs with the private key; all other microservices verify with the public key without needing the secret. Add a <code>kid</code> (key ID) claim to support zero-downtime key rotation by running old and new keys in parallel.</p>'
    ),
    @('JwtAuthenticationFilter',
      '<p><code>OncePerRequestFilter</code> guarantees the filter runs exactly once per request even in servlet include/forward scenarios. Populating the <code>SecurityContext</code> early in the chain ensures all downstream authorization checks (<code>anyRequest().authenticated()</code>) work correctly. The null-safe bearer extraction pattern &mdash; check header exists, check prefix, then extract &mdash; prevents NPEs on unauthenticated requests to public endpoints.</p>',
      '<p>A deleted user with a valid JWT can still pass authentication &mdash; the filter only checks JWT signature and expiry, not whether the user still exists. If <code>UserDetailsService</code> throws (e.g. DB is down during the user lookup), the filter may proceed with an empty <code>SecurityContext</code>, causing a confusing <code>NullPointerException</code> instead of a clean 503.</p>',
      '<p>Add a short-lived Redis blacklist of deleted or suspended user IDs &mdash; check it after JWT validation with O(1) lookup. Wrap the <code>UserDetailsService.loadUserByUsername()</code> call in a try-catch that clears the context and returns 503 Service Unavailable if the DB is unreachable. Log every JWT validation failure with the reason code for security monitoring.</p>'
    ),
    @('UserDetailsServiceImpl',
      '<p>Spring Security requires a <code>UserDetailsService</code> bean even in a JWT-based flow &mdash; it bridges the custom <code>User</code> entity to Spring''s authentication framework, enabling <code>@PreAuthorize("isAuthenticated()")</code> and direct <code>SecurityContextHolder</code> access. <code>@Service</code> registration lets Spring Security auto-discover it without explicit wiring.</p>',
      '<p><code>loadUserByUsername()</code> is called on every authenticated request &mdash; a full DB roundtrip per API call. At production traffic this adds latency and DB load that grows linearly with request rate. The method creates a new <code>UserDetails</code> object on every call with no caching. The parameter is named <code>username</code> but actually receives a UUID string &mdash; a semantic mismatch that confuses developers.</p>',
      '<p>Eliminate the DB call for the common case: since the <code>userId</code> is already in the JWT, construct a minimal <code>UsernamePasswordAuthenticationToken</code> directly from the JWT claims without querying the database. Only hit the DB when roles or permissions must be verified, and cache those lookups with <code>@Cacheable</code> and a short TTL.</p>'
    )
)
Update-File 'study-guide-06-security.html' $p06

# ---- FILE 07 ----
$p07 = @(
    @('AuthController',
      '<p><code>@RequestMapping("/auth")</code> groups all authentication endpoints with a common path prefix. The thin controller delegates all business logic to <code>AuthService</code> &mdash; the controller only validates input, delegates, and maps HTTP semantics. <code>@Valid</code> on each <code>@RequestBody</code> ensures DTO validation runs before the service method is called. <code>@Tag(name = "Authentication")</code> groups endpoints in the Swagger UI sidebar.</p>',
      '<p>No rate limiting means <code>/auth/login</code> can receive thousands of requests per second from a credential-stuffing bot; <code>/auth/register</code> can be spammed to enumerate valid emails. No request size limit means an oversized JSON body causes memory pressure. There is no idempotency mechanism &mdash; a slow network causing a double-submit can trigger two concurrent registrations.</p>',
      '<p>Add Bucket4j or a Spring <code>HandlerInterceptor</code> for rate limiting &mdash; 5 attempts per IP per minute for <code>/auth/login</code>, 3 per minute for <code>/auth/register</code>, returning 429 with <code>Retry-After</code>. Register a Micrometer counter for login failures (<code>auth.login.failure</code>) to drive Grafana alerts. Set <code>spring.servlet.request.max-content-size=4KB</code> to reject oversized payloads at the container level.</p>'
    ),
    @('UserController',
      '<p>A separate controller for user management keeps authentication concerns (login/register) isolated from profile concerns (view/update). The controller extracts the authenticated <code>userId</code> from the <code>SecurityContext</code> rather than from a URL path parameter &mdash; this prevents IDOR (Insecure Direct Object Reference) vulnerabilities where a user changes the ID in the URL to access another user''s profile.</p>',
      '<p>If <code>userId</code> extraction is done manually via <code>SecurityContextHolder.getContext().getAuthentication().getName()</code> in every method, it is verbose and error-prone &mdash; a developer could accidentally use a request parameter instead, creating an IDOR vulnerability. There are no admin endpoints for viewing or deactivating user accounts.</p>',
      '<p>Use <code>@AuthenticationPrincipal</code> as a method parameter &mdash; Spring Security automatically injects the authenticated principal, making the intent explicit and eliminating boilerplate. Add admin-only endpoints with <code>@PreAuthorize("hasRole(''ADMIN'')")</code> for user management. Add <code>PATCH /user/profile</code> with partial update semantics to update individual fields without sending the full profile.</p>'
    )
)
Update-File 'study-guide-07-controller.html' $p07

# ---- FILE 08 ----
$p08 = @(
    @('AuthService',
      '<p><code>@Transactional</code> on all mutating methods ensures atomicity &mdash; the token rotation in <code>refresh()</code> (revoke old, issue new) must be atomic so that a failed insert does not leave the old token permanently revoked. <code>@RequiredArgsConstructor</code> with <code>final</code> fields guarantees all dependencies are injected at construction time, failing fast on a missing bean rather than throwing an NPE at the first method call.</p>',
      '<p><code>register()</code> calls <code>existsByEmail()</code> then <code>save()</code> &mdash; a TOCTOU race where two concurrent registrations with the same email can both pass the check before either inserts, hitting the DB UNIQUE constraint and throwing <code>DataIntegrityViolationException</code> instead of the intended <code>EmailAlreadyExistsException</code>. The <code>hashToken()</code> method wraps <code>NoSuchAlgorithmException</code> in <code>IllegalStateException</code> &mdash; this can never happen (SHA-256 is always available in the JVM), adding dead code that obscures intent.</p>',
      '<p>Remove <code>existsByEmail()</code> and catch <code>DataIntegrityViolationException</code> from <code>save()</code>, re-throwing as <code>EmailAlreadyExistsException</code> &mdash; race-condition-free and semantically correct. Publish domain events (<code>UserRegistered</code>, <code>UserLoggedIn</code>) via <code>ApplicationEventPublisher</code> after successful operations &mdash; enables audit logging and Kafka event emission without coupling <code>AuthService</code> to those concerns.</p>'
    ),
    @('UserService',
      '<p><code>@Transactional(readOnly = true)</code> on <code>getProfile()</code> signals to Hibernate to skip dirty checking (a performance win) and enables Spring to route reads to a PostgreSQL read replica in a multi-node setup. A dedicated <code>UserService</code> separate from <code>AuthService</code> keeps profile management decoupled &mdash; <code>UserService</code> never touches tokens or password hashing, keeping each class focused on a single concern.</p>',
      '<p><code>updateProfile()</code> only allows updating <code>fullName</code> &mdash; adding a new updatable field like <code>avatarUrl</code> requires changing the DTO, service, and controller in lockstep. Without <code>@Version</code> on the <code>User</code> entity, concurrent profile updates produce a lost update: two requests both read the same state, modify it, and the second write silently overwrites the first with no error.</p>',
      '<p>Add <code>@Version Long version</code> to <code>User</code> and handle <code>OptimisticLockingFailureException</code> in <code>UserService</code> by retrying or returning 409 Conflict. Add a dedicated <code>updateStorageQuota(UUID userId, long deltaBytes)</code> method using SQL atomic increment &mdash; <code>UPDATE users SET storage_used_bytes = storage_used_bytes + ?</code> &mdash; rather than read-modify-write to prevent drift under concurrent multi-device uploads.</p>'
    )
)
Update-File 'study-guide-08-service.html' $p08

Write-Host "`nDone. All 8 study guide files updated."
