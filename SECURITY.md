# Security Policy

## Supported versions

Until 1.0, only the latest released version is supported.

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private reporting — *Security* → *Report a vulnerability* on this
repository — or email bhargav.savasani@indianic.com. You should get an
acknowledgement within a few days.

Please include what an attacker gains, the affected version, and the smallest
reproduction you have.

## What counts as a vulnerability here

LatencyScope records URLs, status codes and timings, and renders them into a
file people forward to each other. The interesting failures are about that
data, not about the network:

- **Recorded data escaping redaction** — a token in a query value, a
  credential in a URL, or a header reaching a record, a file or the JSON.
- **Injection into a report** — a recorded URL or a `mark()` label executing
  as script when the HTML is opened, or a CSV cell executing as a formula in a
  spreadsheet. Both of these have been real bugs in this project and both are
  covered by tests; a new way through is a vulnerability.
- **A report landing somewhere it can be read by another app.**

## What does not

- Anything requiring the attacker to already control the app's own code.
- The report's contents being sensitive *by design*. It carries internal
  hostnames and route shapes, which is why both report screens are
  `exported="false"` and why the README says to read a report before
  attaching it to a ticket.
- `latencyscope-noop` doing nothing. That is the point of it.
