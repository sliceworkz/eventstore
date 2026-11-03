# Security policy

## Reporting a vulnerability

Please do not report security vulnerabilities through public GitHub issues, discussions or pull
requests.

Report them privately through GitHub instead: on the
[Security tab](https://github.com/sliceworkz/eventstore/security) of this repository, choose
**Report a vulnerability** (or go straight to
[the advisory form](https://github.com/sliceworkz/eventstore/security/advisories/new)). Only the
maintainers see the report.

Please include what you can of:

- the affected module(s) and version(s)
- what an attacker can do, and under which conditions
- the steps or the code to reproduce it
- a suggested fix, if you have one

We acknowledge a report, keep you informed while we look into it, and agree with you on when the
fix and the advisory are published. We credit you in the advisory unless you prefer otherwise.

## Supported versions

Security fixes are made on the latest release.

## Vulnerabilities in dependencies

A vulnerability in a library this project depends on is reported to that library. Every release
carries a software bill of materials that lists those dependencies; see
[Software bill of materials](README.md#software-bill-of-materials) in the README.
Every build scans that SBOM for known vulnerabilities; how a finding is handled is in
[SECURITY-RELEASES.md](SECURITY-RELEASES.md).
