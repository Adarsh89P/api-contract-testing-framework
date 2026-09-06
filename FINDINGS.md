# Findings

Twelve defects in [Restful-Booker](https://restful-booker.herokuapp.com), each
reproduced by a test in this repository. They are grouped by what a consumer of
the API would actually suffer, not by endpoint.

Every one is pinned as a **characterisation test**: the test asserts the defect
*as it stands today*, with a failure message that says the defect may have been
fixed and names what to update. That way a provider fix arrives as one loud,
self-explaining failure rather than as a mystery break somewhere unrelated three
weeks later.

| # | Severity | Summary | Test |
|---|----------|---------|------|
| 1 | Low | `GET /ping` answers `201 Created` | [PingTest.pingReturnsCreatedInsteadOfOk](src/test/java/com/adarsh/tests/health/PingTest.java) |
| 2 | **Critical** | Failed login answers `200 OK` with a `reason` body | [AuthTest.badCredentialsAnswerTwoHundredWithoutAToken](src/test/java/com/adarsh/tests/auth/AuthTest.java) |
| 3 | Low | `POST /booking` answers `200`, no `Location` header | [CreateBookingTest.createAnswersOkInsteadOfCreated](src/test/java/com/adarsh/tests/booking/CreateBookingTest.java) |
| 4 | Medium | `DELETE` answers `201 Created` | [UpdateDeleteBookingTest.deleteAnswersCreatedInsteadOfNoContent](src/test/java/com/adarsh/tests/booking/UpdateDeleteBookingTest.java) |
| 5 | **Critical** | Incomplete payload answers `500` | [BookingValidationTest.incompletePayloadCausesAServerError](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java) |
| 6 | **Critical** | A stay ending before it starts is accepted | [BookingValidationTest.invertedStayIsAccepted](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java) |
| 7 | **Critical** | A negative total price is accepted | [BookingValidationTest.negativeTotalPriceIsAccepted](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java) |
| 8 | Medium | Unauthenticated write answers `403`, not `401` | [UpdateDeleteBookingTest.unauthenticatedRequestUsesForbiddenInsteadOfUnauthorized](src/test/java/com/adarsh/tests/booking/UpdateDeleteBookingTest.java) |
| 9 | **Critical** | `418 I'm a Teapot` for a valid `Accept` list | [ContentNegotiationTest.unsupportedAcceptTypeAnswersTeapot](src/test/java/com/adarsh/tests/booking/ContentNegotiationTest.java) |
| 10 | Medium | Repeat `DELETE` answers `405`, not `404` | [UpdateDeleteBookingTest.deletingTwiceAnswersMethodNotAllowed](src/test/java/com/adarsh/tests/booking/UpdateDeleteBookingTest.java) |
| 11 | **Critical** | An unparseable date is persisted as `0NaN-aN-aN` | [BookingValidationTest.unparseableDateIsPersistedAsCorruptData](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java) |
| 12 | Low | `"999"` is silently coerced to `999` | [BookingValidationTest.stringPriceIsCoercedToANumber](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java) |

---

## The three that matter most

### FINDINGS-2 — a failed login is `200 OK`

```
POST /auth   {"username":"admin","password":"wrong"}
→ HTTP 200   {"reason":"Bad credentials"}
```

This is the most instructive defect in the API, because of what it does to the
tests rather than to the client.

A negative auth test written the obvious way — `expect(401)`, or even the more
careful `expect(not 200)` — **passes here for every wrong password anyone will
ever try**. It also passes if the endpoint is deleted and replaced with a stub.
It is a test that can never fail, and it sits in the suite looking like coverage.

The consequence for a client is worse. Read the status, take the `token` field,
get `null`, and carry on: the failure surfaces much later as an unexplained
`403` on some unrelated booking call, a long way from the login that actually
went wrong.

Two things in this repository address it:

- [`AuthManager.authenticate`](src/test/java/com/adarsh/core/AuthManager.java)
  treats a `200` without a token as a failure and throws, with the server's
  `reason` in the message. Nothing downstream can proceed on a null token.
- [`auth-token.json`](src/test/resources/schemas/auth-token.json) expresses the
  same fact as a contract, and needs `oneOf` to do it: success carries `token`
  and only `token`, failure carries `reason` and only `reason`. A body with both
  or neither is a break that no status assertion would ever notice.

**Recommendation:** `401 Unauthorized` with a `WWW-Authenticate` header. Until
then, no client should trust the status line of this endpoint.

### FINDINGS-11 — a bad date is stored as corrupt data

```
POST /booking   {..., "bookingdates":{"checkin":"not-a-date","checkout":"2026-12-01"}}
→ HTTP 200      {..., "bookingdates":{"checkin":"0NaN-aN-aN","checkout":"2026-12-01"}}
```

The API parses `"not-a-date"` with a date routine, gets `NaN`, stringifies the
result and **persists it**. `GET` returns the same value, so the record is
permanently corrupt.

This is worse than a rejected request in every direction. The write succeeded, so
the caller has no reason to retry. The stored value violates the API's own
response schema, so a client that parses `checkin` fails on a booking *it did not
create* — the blast radius is other people's code. And because it is a valid JSON
string, nothing downstream notices until a date parser throws.

The test asserts both halves: the corrupt echo, and that a subsequent `GET`
fails schema validation. Rejecting the request with a `400` would have been the
smaller problem by a wide margin.

**Recommendation:** validate the date on the way in and answer `400`.

### FINDINGS-9 — `418 I'm a Teapot` for a perfectly valid `Accept`

```
POST /booking   Accept: application/json, text/json
→ HTTP 418      I'm a Teapot        (content-type: text/plain)
```

`Accept` is a *preference list*. A server that can produce `application/json`
must serve it, not reject the whole request because a sibling entry is
unrecognised. And when negotiation genuinely fails, the status is `406 Not
Acceptable`; `418` comes from an April Fools RFC.

This one cost the most time to find, because of where it bites:

```java
.setAccept(ContentType.JSON)   // Rest-Assured sends FOUR media types
```

Rest-Assured's `ContentType.JSON` expands to `application/json,
application/javascript, text/javascript, text/json` — which is exactly the
header this API rejects. So the default that any Rest-Assured user reaches for
makes **every write in the framework fail**, with a `text/plain` body that no
JSON assertion can explain and a status nobody thinks to look up.

The fix is one string in
[`SpecFactory`](src/test/java/com/adarsh/core/SpecFactory.java), and
[`ContentNegotiationTest.specFactorySendsBareApplicationJson`](src/test/java/com/adarsh/tests/booking/ContentNegotiationTest.java)
guards it, so swapping the bare string back to `ContentType.JSON` fails with an
explanation instead of scattering 418s across the suite.

**Recommendation:** honour the preference list; use `406` when nothing in it can
be served.

---

## The rest

**FINDINGS-5 — incomplete payload answers `500`.** `{"firstname":"OnlyAName"}`
produces `HTTP 500` and a plain-text body. A missing required field is a client
error: it belongs in the 4xx range with a message naming what was missing.
Instead the caller learns nothing, and the provider's error-rate dashboard shows
an outage that is really just bad input.

**FINDINGS-6 — inverted and zero-night stays are accepted.** A checkout nine days
before checkin is stored without complaint, as is a checkout on the checkin date.
This is why [`BookingDates`](src/test/java/com/adarsh/models/BookingDates.java)
rejects both by construction, and why the tests that probe it post raw JSON: a
payload the model refuses to build is exactly the payload under test, and
weakening the model to express it would remove the guard everywhere else.

**FINDINGS-7 — negative total price accepted.** `-500` is stored and reads back
unchanged. Nothing constrains the field to a sensible range.

**FINDINGS-12 — string price coerced.** `"999"` is accepted and reads back as the
number `999`. Harmless alone, but it means the API's working definition of a
valid price is "anything JavaScript will coerce" — which is why every schema here
says `integer` rather than `number`.

**FINDINGS-4 and FINDINGS-10 — delete semantics.** A successful `DELETE` answers
`201 Created`, which is what a client gets for making something. Deleting the
same booking twice answers `405 Method Not Allowed`, because once the record is
gone the route stops matching — so a caller retrying a delete cannot tell
"already gone" from "this endpoint does not support DELETE". Correct would be
`204` then `404`. Note the practical cost:
[`BaseApiTest.deleteCreatedBookings`](src/test/java/com/adarsh/core/BaseApiTest.java)
has to accept `200`, `201`, `404` *and* `405` as "the booking is gone", which is
a lot of ambiguity to absorb in a teardown.

**FINDINGS-8 — `403` where `401` belongs.** A write with no credentials at all
answers `403 Forbidden` and no `WWW-Authenticate` header. `403` means
"authenticated, but not allowed"; a client cannot distinguish "log in" from "you
may never do this".

**FINDINGS-1 and FINDINGS-3 — creation semantics.** `GET /ping` answers `201
Created` for a health probe that creates nothing. `POST /booking`, which does
create something, answers `200` with no `Location` header, so a client must parse
the body to find the id of the resource it just made. The two are exactly
backwards.

---

## Correct behaviour worth recording

Not everything here is broken, and a few behaviours are asserted precisely
*because* they are currently right and a regression would be expensive:

- An unrecognised field in a create request is dropped, not echoed. If that
  changed, `additionalProperties: false` would start failing across every schema
  at once, and
  [`unknownFieldIsIgnored`](src/test/java/com/adarsh/tests/booking/BookingValidationTest.java)
  is the test that says why.
- `PATCH` changes only the fields supplied and leaves the rest untouched.
- A filter matching nothing returns `[]` with a `200`, not a `404`.
- A fabricated token is refused, so the token is checked against server state
  rather than merely parsed.
- Syntactically invalid JSON is rejected with a `400` — the `200` in FINDINGS-2
  is specific to a *well-formed* request carrying wrong values.

## One quirk that is not a defect

The session token goes in a **cookie named `token`**, not in an `Authorization:
Bearer` header. That is a documented choice, not a mistake — but it silently
breaks any client built on the usual bearer assumption, because the header is
ignored and the request is refused as if no credentials were sent.
[`bearerHeaderIsNotAccepted`](src/test/java/com/adarsh/tests/booking/UpdateDeleteBookingTest.java)
asserts both halves: the header is rejected, the cookie works.

## Reproducing

Every finding is reachable with `curl`. For example, FINDINGS-11:

```bash
curl -s -X POST https://restful-booker.herokuapp.com/booking \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"firstname":"A","lastname":"B","totalprice":9,"depositpaid":true,
       "bookingdates":{"checkin":"not-a-date","checkout":"2026-12-02"}}'
# {"bookingid":...,"booking":{...,"bookingdates":{"checkin":"0NaN-aN-aN",...}}}
```

Note the explicit `Accept: application/json`. Send a list and you get FINDINGS-9
instead.
