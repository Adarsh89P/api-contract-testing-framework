# API Contract Testing Framework

REST API test framework for [Restful-Booker](https://restful-booker.herokuapp.com)
— Java 21, TestNG, Rest-Assured, JSON Schema, and Pact consumer contracts.

The target is a real, live, public API with real authentication, full CRUD, and
**genuine defects**. Those defects are the point. A framework built against a
well-behaved mock never has to decide what to do when the login endpoint returns
`200` for a wrong password, or when the client library's default `Accept` header
gets the whole request rejected as a teapot. Both happened here, and the design
decisions below are the answers.

[**FINDINGS.md**](FINDINGS.md) documents twelve defects, each pinned by a test.

---

## Quick start

```bash
# Read-only. No credentials needed, creates nothing, safe against any environment.
mvn -Psmoke test

# Full suite. Creates bookings and cleans them up afterwards.
export BOOKER_USERNAME=admin
export BOOKER_PASSWORD=password123
mvn test

# Pact consumer contracts. Separate profile, JUnit 5, no network.
mvn -Pcontract test

# Fails if any test class is in no suite.
bash scripts/verify-suite-coverage.sh
```

> Restful-Booker's credentials are published in its own documentation, so they
> are not secret. They are still supplied as environment variables rather than
> committed, because the *mechanism* is what matters: point `BASE_URL` at a
> private instance and the same commands work with real secrets substituted and
> nothing else changed.

---

## The trap that shapes everything

`POST /auth` returns **HTTP 200 for a failed login.**

```
POST /auth   {"username":"admin","password":"wrong"}
→ HTTP 200   {"reason":"Bad credentials"}
```

If you assert only on the status code, **every negative auth test you will ever
write passes.** So does one written against a stub. So does one written against
an endpoint that has been deleted. The test looks like coverage, sits in the
suite forever, and can never fail.

Three parts of this repository exist because of that single fact:

1. [`AuthManager.authenticate`](src/test/java/com/adarsh/core/AuthManager.java)
   treats a `200` without a token as a **failure and throws**, carrying the
   server's `reason` in the message. Without it, `AuthManager` hands out `null`
   and the problem resurfaces much later as an unexplained `403` on some
   unrelated booking test — a long way from the login that actually went wrong.
2. [`AuthToken`](src/test/java/com/adarsh/models/AuthToken.java) models *both*
   arms, because the status line genuinely carries no information here.
3. [`auth-token.json`](src/test/resources/schemas/auth-token.json) needs
   **`oneOf`** to express it: success carries `token` and only `token`, failure
   carries `reason` and only `reason`. A body with both, or with neither, is a
   contract break that no status assertion would ever notice.

That is why the schema for this one endpoint looks different from all the
others — and it is the cleanest illustration in the repo of why "assert the
status code" is not a test strategy.

---

## Design decisions

### Config precedence: `-D` > env var > properties file

[`FrameworkConfig`](src/test/java/com/adarsh/config/FrameworkConfig.java) is an
Owner interface, and the `@Sources` order *is* the precedence rule. Nothing else
in the codebase calls `System.getenv` or `System.getProperty` to read a setting.

One trap worth knowing: Owner's default is `LoadType.FIRST`, which consults only
the **first source that loads at all** — and `system:properties` always loads. On
the default policy the properties file is never opened and every value silently
comes from `@DefaultValue`. `@LoadPolicy(LoadType.MERGE)` is what actually
produces the intended precedence, and
[`ConfigPrecedenceTest`](src/test/java/com/adarsh/tests/config/ConfigPrecedenceTest.java)
asserts the policy and the source order directly rather than trusting a comment
to stay true. `BASE_URL` deliberately has no `@DefaultValue`, so a regression
that stops the file being read fails loudly instead of being masked.

Keys are `UPPER_SNAKE_CASE` because that is the one spelling valid in all three
sources at once — Owner matches environment keys exactly, and CI systems will not
accept dots in an environment variable name.

**Credentials resolve lazily and have no default.** `ConfigReader.credentials()`
throws a message naming the settings to fix, and only when something actually
asks — so the read-only smoke suite runs with no secrets configured at all. CI
runs that job with the variables explicitly blanked, so if the smoke suite ever
starts needing credentials, that job is where it shows up.

### Token cached with a conservative TTL

Not per-request, not forever. Re-authenticating on every call roughly triples
traffic against a shared free-tier service; caching forever is a bet that the
provider will never start expiring tokens, and that bet fails as a wave of
unrelated `403`s.

The cache is **static, not thread-local**. One token is valid on every thread; a
thread-local cache issues one login per worker for no benefit.

### The token goes in a cookie

Restful-Booker reads it from a cookie named `token`, not from an
`Authorization: Bearer` header. An API quirk rather than a mistake — so
[`SpecFactory`](src/test/java/com/adarsh/core/SpecFactory.java) has no bearer
variant, and
[`bearerHeaderIsNotAccepted`](src/test/java/com/adarsh/tests/booking/UpdateDeleteBookingTest.java)
asserts both halves: the header is ignored, the cookie works.

### Schema validation on every JSON response

Strict on purpose:

- `additionalProperties: false` — a field the provider adds is a **visible
  break**, not a silent one.
- `"type": "integer"` on `totalprice`, not `number` — a price arriving as `120.5`
  fails.
- `"type": "boolean"` on `depositpaid`, not `string` — `"true"` is not `true`.

[`SchemaGuardTest`](src/test/java/com/adarsh/tests/schema/SchemaGuardTest.java)
exercises the schemas offline against hand-written documents, because *a schema
that accepts everything is worse than no schema* — it produces a green tick that
means nothing. Each strictness above has a test proving it rejects the document
it is meant to reject.

`/ping` is the one endpoint with no schema: its body is the bare word `Created`.
`PingTest` asserts it is still not JSON, so the gap stays deliberate.

### Cleanup lives in the base class

Restful-Booker is a shared public instance. Every booking a run creates is litter
in the next person's `GET /booking`.
[`BaseApiTest`](src/test/java/com/adarsh/core/BaseApiTest.java) registers ids **at
creation**, deletes them in `@AfterClass`, and **logs failures rather than
throwing** — a broken teardown is an operational nuisance, and letting it fail
the build turns a green product into a red pipeline that buries the result which
actually mattered.

Per-test cleanup is done in the happy path and forgotten in the four negative
tests that also happened to create something. In the base class it is
unconditional.

### No hardcoded dates, no fixed names

Dates are relative to `LocalDate.now()`, so a suite written today does not
quietly start booking stays in the past next year. Names come from Datafaker with
a numeric suffix, because `GET /booking?firstname=John` filters across
**everyone's** bookings on a shared API — with a fixed name, any "my booking is in
the list" assertion depends on strangers.

### Invalid payloads are raw JSON, not models

[`BookingDates`](src/test/java/com/adarsh/models/BookingDates.java) rejects a
check-out that is not strictly after check-in, making an invalid stay
unrepresentable. The API accepts inverted ranges anyway (FINDINGS-6), so the
tests that probe that defect **post raw JSON strings**.

That is deliberate rather than lazy. A payload the model refuses to build is
exactly the payload under test, and weakening the model to express it would
remove the guard everywhere else. A test that cannot express its own input
without disabling a safety rail is a test that lies about what it sent.

### Suite membership is the only way a test runs

Surefire is driven by `<suiteXmlFiles>`. A class that compiles, passes review and
merges still executes **exactly zero times** if nobody added a `<class>` line —
and that failure is invisible, because the build is green and the report is
simply shorter than anyone counted.

[`scripts/verify-suite-coverage.sh`](scripts/verify-suite-coverage.sh) compares
discovered `*Test.java` against suite membership and fails **both ways**: a class
in no suite, and a suite entry naming a class that no longer exists. It runs as
the first CI job, so it fails in seconds rather than after the network suite.

### Allure filter attached once, in `SpecFactory`

Not per call. A per-call filter only ever covers the calls someone remembered,
and the one that gets forgotten is invariably the one that fails in CI with no
request body in the report.

> Note: Allure's `@Step`/`@Attachment` annotations need the AspectJ load-time
> weaver, and no `aspectjweaver` release supports this JDK's class-file version
> yet. The framework uses the programmatic API (`Allure.step`,
> `Allure.addAttachment`) instead, which needs no agent — so there is
> deliberately no `-javaagent` in the build.

### Pact is a separate profile, because it has to be

Pact ships **no TestNG runner**. Consumer tests are JUnit 5, live under
`contract/`, and run only under `-Pcontract` with the JUnit platform provider.
The default run excludes `**/contract/**` outright: point one surefire execution
at both providers and **one of them silently runs nothing** while the build stays
green.

The pact tests drive the real `SpecFactory` and `AuthManager` against Pact's mock
provider by repointing `BASE_URL`, so the contract describes the client that
actually exists rather than a hand-rolled HTTP call written to match it. The
bad-credentials interaction is specified as `200` with a `reason` body — that is
what the provider does and what this consumer is built around, and writing `401`
there would produce a contract the provider cannot verify and a client that
breaks in production with a green pact.

---

## Layout

```
src/test/java/com/adarsh/
├── config/     FrameworkConfig (Owner interface), ConfigReader
├── core/       Endpoints, SpecFactory, AuthManager, AuthenticationException, BaseApiTest
├── models/     Credentials, AuthToken, Booking, BookingDates, BookingResponse
├── utils/      SchemaValidator, BookingFactory
├── tests/      config/ models/ schema/ health/ auth/ booking/
└── contract/   Pact consumer tests (JUnit 5, -Pcontract only)

src/test/resources/
├── config/     config.properties, log4j2.xml
├── schemas/    booking, booking-created, booking-ids, auth-token
└── suites/     smoke.xml, regression.xml
```

## Test inventory

| Area | Tests | Network | In smoke |
|------|-------|---------|----------|
| Config precedence | 8 | no | yes |
| Models & serialisation | 7 | no | yes |
| Schema guards | 9 | no | yes |
| Health / `ping` | 3 | yes | yes |
| Auth | 8 | yes | no |
| Booking — create | 5 | yes | no |
| Booking — read | 4 | yes | no |
| Booking — update & delete | 11 | yes | no |
| Booking — validation | 10 | yes | no |
| Content negotiation | 5 | yes | no |
| **Regression total** | **70** | | |
| Pact consumer (`-Pcontract`) | 3 | mock only | — |

The negative suite (26 tests across validation, content negotiation, and
authorisation) is deliberately larger than the happy path. Nine of the twelve
findings live there.

## Configuration

| Key | Default | Purpose |
|-----|---------|---------|
| `BASE_URL` | from `config.properties` | Target API |
| `BOOKER_USERNAME` | *(none)* | Required only for write tests |
| `BOOKER_PASSWORD` | *(none)* | Required only for write tests |
| `HTTP_CONNECT_TIMEOUT_MS` | `30000` | Free-tier dyno cold start |
| `HTTP_RESPONSE_TIMEOUT_MS` | `60000` | Free-tier dyno cold start |
| `TOKEN_TTL_SECONDS` | `600` | Token cache lifetime |
| `LOG_HTTP` | `false` | Dump request/response to console |

Any of these works as `-DKEY=value`, as an environment variable, or as a line in
`config.properties`, in that order of precedence.

The timeouts are generous because Heroku's free tier **sleeps**: the first
request of a cold run routinely takes tens of seconds. CI wakes the dyno with a
retrying `curl` before the suite starts, so the cold-start cost stays out of the
first assertion's timing.

## Reports

```bash
mvn test
allure serve target/allure-results
```

Every request and response is attached, along with the schema each response was
validated against and a per-class cleanup report listing what was deleted and
what was left behind.

## CI

[`.github/workflows/api-tests.yml`](.github/workflows/api-tests.yml) runs suite
coverage first (seconds), then smoke with credentials explicitly blanked, then
regression, with the Pact job on its own profile. It also runs on a daily
schedule — the value of these tests is catching the provider changing underneath
us, and that does not happen on our commits.
