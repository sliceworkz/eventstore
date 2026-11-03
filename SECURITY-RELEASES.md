# Known vulnerabilities: the scan, and releasing anyway

Every build scans this repository's software bill of materials (SBOM) for known vulnerabilities with
[Grype](https://github.com/anchore/grype). This page says what the scan does, what to do when it stops a
build, and how to release anyway when that cannot wait: the **emergency release**.

## Where the scan runs

| Workflow | What it does | Stops the build? |
|---|---|---|
| CI (every push and pull request) | scans the SBOMs the build writes | yes, on a High or Critical vulnerability with a fix available |
| `create-release-tag-and-bump` | builds without tests and scans, **before** the release version is set and tagged | yes, and then nothing has changed: no commit, no tag, no version bump, so the version number is not lost |
| `build-and-deploy-from-release-tag` | scans what is released (and, for an image, the image) | no: it only reports, since the release is tagged by then |

Only a vulnerability that is **High or Critical** and **has a fixed version** stops a build. Lower severities
and vulnerabilities nobody has fixed yet are reported, never blocking.

What is scanned is listed in `.github/vulnerability-scan/targets`; the scan itself is
`.github/vulnerability-scan/scan.sh`. Decisions about findings are kept in **`.vex/openvex.json`**, the VEX
file ([OpenVEX](https://openvex.dev)): one statement per vulnerability per package version.

## When the scan stops a build

1. **Open the failed run's summary** (the run's page on GitHub, the *Summary* tab). The scan's table lists
   each finding: severity, the vulnerability's id (`GHSA-…`), the package and its version, the version that
   fixes it, and the package's id (`pkg:…`). The `vulnerability-reports` artifact of the run holds the same
   as text and JSON.
2. **Decide, per finding, which of three applies:**

   | Situation | What to do |
   |---|---|
   | the fix can go in now | **A. Fix it**: move to the fixed version |
   | it does not apply to us | **B. Not affected**: record why, in the VEX file |
   | it applies, and the release cannot wait for the fix | **C. Emergency release**: record it as affected, with what is done about it |

### A. Fix it (the normal way)

Move the package to the version in the *Fixed in* column, then build again.

- **A direct dependency:** raise its version in the pom, or in `package.json` for an npm package.
- **A transitive dependency** (pulled in by another library):
  - **Maven:** pin the fixed version in the parent pom's `<dependencyManagement>`, with a comment saying why.
    Drop the pin again once the library that brings it in ships the fixed version itself.
  - **npm:** add an `overrides` entry in `package.json`.
- Often Dependabot has a pull request for it already.

### B. Not affected

The package is there but the vulnerability does not apply: the vulnerable code is never called, a feature it
needs is off, it is only exploitable in a setup we do not have. Add a statement to `.vex/openvex.json`:

```json
{
  "vulnerability": { "name": "GHSA-xxxx-xxxx-xxxx" },
  "products": [ { "@id": "pkg:maven/com.example/some-library@1.2.3?type=jar" } ],
  "status": "not_affected",
  "justification": "vulnerable_code_not_in_execute_path",
  "impact_statement": "We never call SomeParser.parse on input from outside; the vulnerability is in that method.",
  "timestamp": "2026-11-03T10:00:00Z"
}
```

`justification` is one of `component_not_present`, `vulnerable_code_not_present`,
`vulnerable_code_not_in_execute_path`, `vulnerable_code_cannot_be_controlled_by_adversary`,
`inline_mitigations_already_exist`. The `impact_statement` says **why**, so whoever reads it later can check
it still holds. A wrong *not affected* is worse than none: it tells people not to patch.

### C. Emergency release: releasing with a known vulnerability

Use this only when the release cannot wait for the fix: it fixes something more urgent, or a client is
blocked. The vulnerability stays in the release, so the record says so honestly (`affected`), says what is
done about it, and the scan lets it through.

1. **Add one statement per finding** to the `statements` of `.vex/openvex.json`, copying the id and the
   package id from the summary table:

   ```json
   {
     "vulnerability": { "name": "GHSA-xxxx-xxxx-xxxx" },
     "products": [ { "@id": "pkg:maven/com.example/some-library@1.2.3?type=jar" } ],
     "status": "affected",
     "action_statement": "Upgrade to some-library 1.2.5 follows in release 0.6.1, planned this week. Until then, disable the XML import (import.xml.enabled=false).",
     "timestamp": "2026-11-03T10:00:00Z"
   }
   ```

   The `action_statement` is required: **what is done about it, and when**, plus any workaround users can
   apply meanwhile.
2. **Raise the `version` of the VEX file** by one and set its `timestamp` to now.
3. **Commit it to the release branch** (`release/<major>.<minor>`), through a pull request like any change,
   with a commit message naming the vulnerability and the reason for releasing anyway. Take the same
   statement to `develop` too, as long as `develop` still has that package version.
4. **Run `create-release-tag-and-bump` again.** The scan now lists the finding under *covered by a statement
   in the VEX file* and lets the release through. The release has the version it would have had.
5. **Fix it in the next release** (A). Then remove the statement from the VEX file, or change it to
   `"status": "fixed"`. A statement only ever covers the package version it names, so it never hides the same
   vulnerability in another version, but a stale one is misleading.

### Rules that make a statement work

- **The vulnerability's id exactly as the summary shows it.** That is usually the GitHub advisory id
  (`GHSA-…`). A statement under the CVE number (`CVE-…`) of the same vulnerability is **not** matched.
- **The package id exactly as the summary shows it**, version included. The `?type=jar` at the end may stay
  or go.
- **A `not_affected` statement needs a `justification` and an `impact_statement`, and an `affected` one an
  `action_statement`.** The scan refuses a statement without them, naming it.
- `under_investigation` is allowed but changes nothing: the finding still stops the build.

## Running the scan yourself

With [Grype](https://github.com/anchore/grype) installed (`brew install grype`, or its install script), after
a `mvn package`:

```bash
.github/vulnerability-scan/scan.sh project gate
```

It prints the same summary as CI. The first run downloads Grype's vulnerability database, about 3 GB unpacked.
