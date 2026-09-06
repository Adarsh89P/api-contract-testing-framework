#!/usr/bin/env bash
#
# Fails the build when a test class is not reachable from any TestNG suite.
#
# Suite membership is the only way a test runs here: surefire is driven by
# <suiteXmlFiles>, so a class that compiles, passes review and is merged still
# executes exactly zero times if nobody added a <class> line for it. That failure
# is invisible - the build is green, the report simply has fewer rows than anyone
# counted. This script is the check that makes it loud.
#
# It also fails the other way, on a suite entry naming a class that no longer
# exists, because TestNG treats an unresolvable class as a suite-level error that
# is easy to scroll past.
#
# Usage:  scripts/verify-suite-coverage.sh
# Exit:   0 all classes accounted for, 1 on any mismatch

set -euo pipefail

cd "$(dirname "$0")/.."

SRC_ROOT="src/test/java"
SUITE_DIR="src/test/resources/suites"

# Pact consumer tests are JUnit 5 and run under -Pcontract, which uses includes
# rather than a suite XML. They are out of scope here by design.
EXCLUDED_PATH_PREFIX="com/adarsh/contract/"

if [ ! -d "$SRC_ROOT" ] || [ ! -d "$SUITE_DIR" ]; then
  echo "ERROR: expected $SRC_ROOT and $SUITE_DIR relative to the repository root" >&2
  exit 1
fi

# Every runnable test class, as a fully qualified name. Abstract classes are
# skipped: BaseApiTest matches *Test.java but TestNG can never instantiate it,
# so listing it in a suite would be an error rather than a fix.
discovered=$(
  find "$SRC_ROOT" -name '*Test.java' -type f \
    | while read -r file; do
        grep -qE '^[[:space:]]*(public[[:space:]]+)?abstract[[:space:]]+class' "$file" \
          || echo "$file"
      done \
    | sed "s|^$SRC_ROOT/||" \
    | grep -v "^$EXCLUDED_PATH_PREFIX" \
    | sed 's|\.java$||; s|/|.|g' \
    | sort -u
)

# Every class named by any suite. Abstract bases and helpers are not listed and
# are not expected to be - only *Test.java files are checked.
declared=$(
  grep -ho 'class name="[^"]*"' "$SUITE_DIR"/*.xml \
    | sed 's|class name="||; s|"$||' \
    | sort -u
)

missing_from_suites=$(comm -23 <(echo "$discovered") <(echo "$declared") || true)
missing_from_source=$(comm -13 <(echo "$discovered") <(echo "$declared") || true)

status=0

if [ -n "$missing_from_suites" ]; then
  status=1
  echo "FAIL: these test classes are in no suite and therefore never run:" >&2
  echo "$missing_from_suites" | sed 's|^|  - |' >&2
  echo >&2
  echo "Add each to $SUITE_DIR/regression.xml (and to smoke.xml if it makes no" >&2
  echo "network calls or is read-only), or delete the class." >&2
  echo >&2
fi

if [ -n "$missing_from_source" ]; then
  status=1
  echo "FAIL: these suite entries name classes that do not exist:" >&2
  echo "$missing_from_source" | sed 's|^|  - |' >&2
  echo >&2
  echo "TestNG reports an unresolvable class as a suite-level error that is easy" >&2
  echo "to miss. Remove the stale <class> line." >&2
  echo >&2
fi

if [ "$status" -eq 0 ]; then
  echo "OK: $(echo "$discovered" | wc -l | tr -d ' ') test classes, all reachable from a suite."
fi

exit "$status"
