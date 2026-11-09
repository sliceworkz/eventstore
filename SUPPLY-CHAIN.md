# Software supply chain: SBOMs, vulnerability scanning and dependency updates

This page explains, for anyone working on this repository and not a security specialist, what is in place to
know **which third-party software the event store library ships**, to **find out when some of it has a known
vulnerability**, and to **keep it up to date**: the words, how the parts fit together, where each part lives,
what it depends on outside the code, and why it is built the way it is.

- What to **do** when a scan finds something, and how to release anyway in an emergency:
  [SECURITY-RELEASES.md](SECURITY-RELEASES.md).
- How someone outside reports a vulnerability in this code: [SECURITY.md](SECURITY.md).

## In short

- Every build writes a **software bill of materials (SBOM)**: the list of every library it ships, with versions.
- Every build **scans** that list against a database of known vulnerabilities. A High or Critical one that has a
  fix **stops the build**, and stops a release **before** its version number is used.
- Every release **publishes** its SBOM, signed by GitHub as coming from this repository's release workflow.
- Every morning, the **latest release of each release line is scanned again**: a vulnerability published after a
  release turns up there, as a GitHub issue.
- **Dependabot** proposes dependency updates as pull requests, and the build tests each one, scan included.

## The words

| Word | What it means here |
|---|---|
| **Software supply chain** | Everything that goes into what we ship and that we did not write: libraries, their own libraries (transitive dependencies), build tools. A vulnerability in any of them is a vulnerability in our product. |
| **SBOM** (software bill of materials) | A machine-readable list of every component a piece of software contains: name, version, and an exact identifier (purl). Like the ingredients list on food packaging. Ours are written by the build, not by hand, so they are always complete and current. |
| **CycloneDX** | The SBOM format we use (version 1.6, JSON), an OWASP standard that every scanner reads. Our SBOM files end in `.cdx.json`. |
| **purl** (package URL) | The exact identifier of a component, version included: `pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.21.8`. A scanner matches vulnerabilities on it, and a VEX statement names a package by it. |
| **CVE, GHSA** | Identifiers of one vulnerability. A **CVE** (`CVE-2026-12345`) is the global id; a **GHSA** (`GHSA-xxxx-xxxx-xxxx`) is GitHub's advisory id for the same thing, and it is what our scanner reports for Java packages. One vulnerability often has both. |
| **Severity** | How bad a vulnerability is, from the advisory: Critical, High, Medium, Low, Negligible. Only **High and Critical** stop a build. |
| **Fix available** | A newer version of the vulnerable package exists without the vulnerability. Only a vulnerability **with** a fix stops a build: one nobody has fixed yet cannot be fixed by us by upgrading, so it is reported and does not block. |
| **Grype** | The scanner, from Anchore (open source). It reads an SBOM, compares every component with its vulnerability database and reports the matches. |
| **Vulnerability database** | Grype's knowledge of all published vulnerabilities, collected from GitHub, the NVD, Linux distributions and others. Anchore publishes a new one every day; every scan downloads the latest (about 3 GB unpacked). |
| **VEX** (Vulnerability Exploitability eXchange) | A statement about one vulnerability in one package version **in our product**: "not affected" (it is there but cannot be exploited here, and why), "affected" (it is there and we accept it for now, and what we do about it), "fixed". Ours is `.vex/openvex.json`, in the [OpenVEX](https://openvex.dev) format. The scanner reads it, so a decision taken once is not asked again. |
| **Attestation** | A signed statement about a file: "this SBOM describes this jar", "this file was built by this workflow from this commit" (**provenance**). Whoever holds the file can check it was made by our workflow and not changed since. |
| **Dependabot** | GitHub's service that watches our dependencies and opens a pull request when a new version comes out (version updates, on a weekly schedule), and at once when a vulnerability is published in one we use (security updates). |
| **Cooldown** | Dependabot waits a few days after a release before proposing it, so a broken or tampered release can be noticed and pulled before it reaches us. |

## How it fits together

```
 code + dependencies
        │
        ▼
  ┌──────────┐   writes    ┌──────────┐   scanned by Grype, with the VEX file
  │  build   │ ──────────▶ │  SBOM(s) │ ──────────────────────────────────────▶ High/Critical with a fix?
  └──────────┘             └──────────┘                                           yes: the build stops
        │                                                                          no:  it goes on
        ▼  a release (create-release-tag-and-bump, then build-and-deploy-from-release-tag)
  published: jars, with the SBOM on the GitHub release
        │  every morning (rescan released versions)
        ▼
  the latest release of each release/X.Y line, scanned again with that day's data
        │
        ▼
  a blocking finding: GitHub issue "Known vulnerabilities in eventstore X.Y.Z"
        │
        ▼
  a patch release (or a VEX statement) on release/X.Y  ──▶  the issue closes itself

  Dependabot, all the while: a pull request per new version; the build (scan included) tests it
```

**1. The build writes the SBOMs.** The CycloneDX Maven plugin (in the root `pom.xml`, `makeAggregateBom` in the
`package` phase) writes one SBOM over all the modules, and each module's own. Test-scoped dependencies are left
out: they are not shipped.

The SBOMs the scan reads are listed in `.github/vulnerability-scan/targets`:

| Name | File | What it covers |
|---|---|---|
| `library` | `target/bom.json` | the library: every module a consumer puts on its classpath |
| `test-kit` | `sliceworkz-eventstore-testing/target/bom.json` | the test kit, kept apart so its test dependencies (JUnit, Testcontainers) are not read as the library's |

**2. Every build scans them.** The CI workflow (`ci.yaml`, on every push and pull request) runs
`.github/vulnerability-scan/scan.sh project gate` after the build. Grype is installed and its database downloaded
at the start of the job, in the background while Maven builds, so the scan itself adds seconds. The verdict is in
the run's summary; the reports are kept as the `vulnerability-reports` artifact.

**3. A release is scanned before it is tagged.** `create-release-tag-and-bump` builds without tests and scans
**before** it sets the release version, commits and tags. A release the scan stops has changed nothing: no tag,
no commit, the version number is not used up. Then `build-and-deploy-from-release-tag` builds the tag,
publishes, and scans what it published again, for the record only (it cannot stop a release that is already
tagged).

**4. Every morning, the released versions are scanned again.** A vulnerability published after a release is one no
build could have stopped. `rescan-releases.yaml` (05:41 UTC daily, or by hand) takes the
**latest release of each `release/X.Y` branch** (its highest `vX.Y.Z` tag), downloads the SBOMs attached to its
GitHub release, and scans them with that day's database and the VEX file **on the release branch**. A
blocking finding opens an issue, **"Known vulnerabilities in eventstore X.Y.Z"** (label `vulnerability`), which GitHub
notifies watchers of; a later finding in the same release is added as a comment; the issue closes itself once
nothing blocking is left, or once a newer patch release replaces that version (which is then scanned instead).
The script is `.github/vulnerability-scan/rescan-releases.py`; what to do with such an issue is in
SECURITY-RELEASES.md.

- A **release line** is a `release/X.Y` branch. Deleting the branch ends its re-scan; its tags (the released code) stay.
- Only the **latest** release of a line is scanned: whoever runs an older patch of that line is expected to move up.
- A release from before SBOMs were published has none to scan, and is listed as "not scanned" in the run's summary.
- A run turns red only when a scan **could not run** (a download, the registry, Grype). Findings never make it red:
  they are what the issues are for. So a red run is worth a look: it means the check is not working.

**5. Dependabot keeps the dependencies moving.** See [Dependency updates](#dependency-updates) below.

## What is where

| Path | What it is |
|---|---|
| `pom.xml` | the CycloneDX Maven plugin's setup (the SBOM of every module and the aggregate); a transitive dependency held at a fixed version (for a vulnerability, or a conflict) has the reason in a comment next to it |
| `.github/vulnerability-scan/scan.sh` | the scan: installs Grype, downloads the database, scans an SBOM or an image with the VEX file, writes the summary |
| `.github/vulnerability-scan/targets` | which SBOMs the build writes, so which ones CI and the release scan |
| `.github/vulnerability-scan/Dockerfile` | one line, `FROM anchore/grype:vX.Y.Z`: the Grype version `scan.sh` uses. Nothing is built from it; it is there so Dependabot can propose Grype updates |
| `.github/vulnerability-scan/rescan-releases.py` | the daily re-scan of the released versions, and its issues |
| `.vex/openvex.json` | the VEX file: our decisions about findings (empty until the first one) |
| `.github/workflows/ci.yaml` | build, tests and scan of every push and pull request |
| `.github/workflows/create-release-tag-and-bump.yaml` | scans, then sets the release version, tags it and moves to the next snapshot |
| `.github/workflows/build-and-deploy-from-release-tag.yaml` | builds the tag, publishes it with its SBOM and attestations, reports a scan |
| `.github/workflows/rescan-releases.yaml` | the daily re-scan |
| `.github/dependabot.yaml` | what Dependabot watches, how often, and how it groups updates |
| `.github/dependency-policy.yaml` | the nightly dependency pipeline's policy: per branch and per level (patch, minor, major) what it does with an update, the cooldown, and what is held |
| `.github/workflows/nightly-dependencies.yaml` | runs the pipeline every night (sliceworkz/software-factory) |
| `SECURITY-RELEASES.md` | what to do when a scan finds something |
| `SECURITY.md` | how to report a vulnerability privately |

## Where the SBOMs are published, and how to read them

- **On the GitHub release** of every version: `sliceworkz-eventstore-<version>.cdx.json`, `sliceworkz-eventstore-testing-<version>.cdx.json`.
- **In the Maven repository** (Maven Central), next to each module's jar, as its `cyclonedx` file
  (`<module>-<version>-cyclonedx.json`), the SBOM of that one module.
- **Attested.** The release workflow signs, with a GitHub artifact attestation, that each SBOM file was made by this
  repository's release workflow from the tagged commit (provenance), and that each SBOM describes the jars it
  belongs to. GitHub signs these through Sigstore, without a key of ours, and logs them in a public transparency
  log. Anyone can check them:

  ```bash
  gh attestation verify sliceworkz-eventstore-api-<version>.jar --repo sliceworkz/eventstore --predicate-type https://cyclonedx.org/bom
  gh attestation verify sliceworkz-eventstore-<version>.cdx.json --repo sliceworkz/eventstore
  ```

An SBOM is plain JSON. To list what one contains:

```bash
jq -r '.components[] | "\(.name) \(.version)"' sliceworkz-eventstore-<version>.cdx.json | sort
```

And `scan.sh` scans any SBOM file:

```bash
.github/vulnerability-scan/scan.sh report sliceworkz-eventstore-<version>.cdx.json mine
```

## Dependency updates

**The nightly dependency pipeline** (`.github/workflows/nightly-dependencies.yaml`, the shared workflow of
`sliceworkz/software-factory`, whose README describes it in full) is on trial here: its policy
(`.github/dependency-policy.yaml`) has `dryRun: true`, so every night it decides per branch what it would
update, scans the branch, and reports in the run's summary and on the `dependency-status` branch, but opens
no pull request. CI keeps its SBOMs as the artifact `sboms` for it. Until the policy is set live and the
Maven entry is removed from `.github/dependabot.yaml`, Dependabot updates the dependencies as below.

Dependabot (`.github/dependabot.yaml`) checks every week, and proposes each new version as a pull request; the
pull request's build tests it, the scan included. What it watches:

- **Maven**: a pull request per new version.
- **The GitHub Actions** the workflows use (`actions/checkout`, `actions/setup-java`, …), in one pull request a week.
  Its build runs the CI workflow with them; the release workflows only run at the next release, so look at that one.
- **Grype itself**, through `.github/vulnerability-scan/Dockerfile`. Merge these when they are green and do not let
  them wait: a newer Grype matches packages better, and once Anchore moves its database to a format an old Grype
  cannot read, the scan stops getting new data.

Two things to know:

- **Cooldown.** Dependabot proposes a new version only a few days after it is released (its default cooldown), so a
  broken or tampered release can be withdrawn before it reaches us. A version you read about today may come
  next week.
- **Security updates** (a vulnerability published in a dependency we use) come at once, outside the schedule,
  when Dependabot security updates are switched on in the repository settings.

## Setup outside the code

What this depends on that is not in the repository, so it is easily forgotten:

| What | Where | Used by |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` | repository secrets (Actions) | publishing a release to Maven Central, signed with GPG, as Central requires |
| `CLOUDREPO_USERNAME`, `CLOUDREPO_PASSWORD` | repository secrets (Actions) | deploying snapshots to CloudRepo |
| The GitHub App **sliceworkz-software-factory** installed on the repository, the organization variable `FACTORY_APP_CLIENT_ID` (the App's Client ID) and secret `FACTORY_APP_PRIVATE_KEY` shared with it, and the App on the bypass list of the ruleset on `develop` and `release/*` | *sliceworkz → Settings → GitHub Apps*, *Secrets and variables → Actions*, *Settings → Rules → Rulesets* | `create-release-tag-and-bump` and `prepare-release-branch` push as the App: a push made with the workflow's own token would not start the release workflow on the tag nor CI on the branch, and a personal access token would act as a person. The token is made per run, lasts an hour and is handed only to the steps that push |
| Workflow permissions | each workflow declares its own (`permissions:`); the re-scan needs `issues: write` | the re-scan opening and closing issues |
| Private vulnerability reporting | *Settings → Code security* | lets someone report a vulnerability privately (SECURITY.md) |
| Dependabot alerts and security updates | *Settings → Code security* | security updates outside the weekly schedule |

GitHub suspends the scheduled workflows of a **public** repository after 60 days without a commit, and sends an
e-mail when it does; the daily re-scan then stops until it is switched on again (*Actions → rescan released
versions → Enable workflow*).

## Links with the other repositories

- This repository is used by eventmodeling, the dashboard, the modeler and the projects generated from the marketplace templates. A vulnerability in one of its dependencies shows up in their scans as well; the fix starts here.
- The same scan, re-scan and procedure are in eventstore, eventmodeling, dashboard, plugins and modeler, and `scan.sh` and `rescan-releases.py` are identical in all five: a change to one belongs in all of them. The projects the marketplace's backend line generates get the build scan and the procedure, not the daily re-scan.

## Decisions, and why

- **Only High and Critical, and only with a fix, stop a build.** Stopping on every Medium, or on vulnerabilities nobody
  can fix yet, would stop most builds for things we cannot act on, and teach everyone to ignore the scan.
- **The scan runs before a release is tagged**, so a stopped release uses up no version number, and the release
  workflow only reports, since it cannot undo a tag.
- **A finding is either fixed or recorded, never silenced.** The VEX file says per vulnerability and package version
  why it does not apply (`not_affected`) or that it is accepted for now and what is done about it (`affected`).
  A statement is tied to one package version, so it never hides the same vulnerability in a later version. Grype has
  no VEX status that lets an accepted finding pass, so `scan.sh` turns each `affected` statement into an ignore rule
  for exactly that vulnerability and package version, and the finding is still listed as accepted.
- **A VEX statement names the vulnerability by the id the scan reports**, usually the GHSA: a statement under the CVE
  number of the same vulnerability is not matched.
- **The re-scan reads the VEX file on the release branch**, not on `develop`, so a decision about a released version is
  taken where its patch releases come from.
- **The re-scan scans the release, not the branch.** A branch can hold fixes not released yet; scanning it would report
  clean while users still run the vulnerable version.
- **Issues, not a red workflow, report findings.** A failing scheduled run only e-mails whoever last changed the workflow,
  and reads as a broken build.
- **Grype is pinned**, and updated through Dependabot, so a new Grype release cannot change a release build's verdict
  unannounced; its database is not pinned, since being current is the point.
- **The vulnerability database is not cached.** It downloads while Maven builds, so it costs no time, and a cached copy
  would take a large share of the repository's Actions cache from the Maven caches.
- **No VEX file is published with a release yet**: it has no statements yet. Once it has, publishing it with the release tells users which findings do not apply.

## Not done yet

- **Publishing the VEX file** with each release, once it has statements.
- **A portfolio view** of every version in use (for example OWASP Dependency-Track), worth it once many versions are in
  the field or clients ask for reporting; the daily re-scan covers the latest release of each line.

## Doing it yourself

- **Run the scan locally**, after `mvn package`, with Grype installed (`brew install grype`): `.github/vulnerability-scan/scan.sh project gate`.
  The first run downloads the database.
- **See the daily re-scan**: *Actions → rescan released versions*; its summary lists every release line, and the
  `rescan-reports` artifact holds the reports. *Run workflow* starts one by hand.
- **See the open findings**: the issues labelled `vulnerability`.
- **Record a decision**, or release anyway: SECURITY-RELEASES.md.
- **End a release line**: delete its `release/X.Y` branch; the next re-scan closes its issue.
