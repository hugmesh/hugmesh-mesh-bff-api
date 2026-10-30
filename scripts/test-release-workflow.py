#!/usr/bin/env python3
"""Shape checks for .github/workflows/release.yml. Pure text, no network.

Run: python3 scripts/test-release-workflow.py   (needs PyYAML; fails, never skips, without it)

What it pins, and why:
  * No pull_request / pull_request_target trigger. This workflow holds packages:write and may
    run on the self-hosted runner; a PR must never reach it.
  * Triggers are exactly: push to main, push of v* tags, workflow_dispatch.
  * version and github-release use the one runs-on line that picks vars.RUNS_ON_PUBLISH only
    for a non-PR event on main or a v* / studio-v* tag, and ubuntu-latest otherwise.
  * The image job (build, push, sign) runs on the GitHub-hosted ubuntu-latest, written
    literally: a published image is never built on a self-hosted runner. It signs the pushed
    DIGEST with cosign keyless and pushes provenance and SBOM attestations, and only it holds
    id-token: write and attestations: write.
  * notify-site is the only other job: hosted, no permissions, v* tags only, after the Release.
  * Image tags come only from the version guard (scripts/release-version.sh); no literal
    :latest anywhere in the workflow.
  * The existing gates stay: the cross-repo package preflight, the BuildKit
    packages_read_token secret, and the refuse-to-overwrite check on release runs.
  * The refuse-to-overwrite step is EXECUTED, not just read: its run block runs the way the
    runner runs it (no shell: key, so `bash -e {0}`) with `docker` stubbed on PATH. Image
    absent -> the step continues; present -> it refuses; any other error -> it fails closed.
    The pre-fix form (`out=$(...)` then `rc=$?`) is run the same way and must die on the
    absent case, so the harness itself is proven able to see the defect.
  * Only the github-release job gets contents: write, and only on a release run.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

try:
    import yaml
except ImportError:  # fail closed: a missing parser must not look like a pass
    print("FAIL PyYAML is not installed; cannot check release.yml", file=sys.stderr)
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WF_PATH = ROOT / ".github" / "workflows" / "release.yml"
RUNS_ON = (
    "${{ fromJSON(github.event_name != 'pull_request' && github.event_name != 'pull_request_target'"
    " && (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')"
    " || startsWith(github.ref, 'refs/tags/studio-v')) && vars.RUNS_ON_PUBLISH || '\"ubuntu-latest\"') }}"
)

failures = 0


def check(desc, ok):
    global failures
    if ok:
        print(f"ok   {desc}")
    else:
        print(f"FAIL {desc}", file=sys.stderr)
        failures += 1


text = WF_PATH.read_text(encoding="utf-8")
wf = yaml.safe_load(text)
# YAML 1.1 reads the bare key `on` as boolean True.
on = wf.get("on", wf.get(True))
jobs = wf.get("jobs", {})

check("triggers are exactly push, workflow_dispatch",
      isinstance(on, dict) and set(on) == {"push", "workflow_dispatch"})
check("no pull_request or pull_request_target trigger anywhere",
      isinstance(on, dict) and not ({"pull_request", "pull_request_target"} & set(on)))
push = (on or {}).get("push") or {}
check("push runs on branch main only", push.get("branches") == ["main"])
check("push runs on v* tags only", push.get("tags") == ["v*"])

check("workflow default permissions are contents: read only",
      wf.get("permissions") == {"contents": "read"})
check("jobs are version, image, github-release, notify-site",
      set(jobs) == {"version", "image", "github-release", "notify-site"})
for name in ("version", "github-release"):
    check(f"job {name} uses the RUNS_ON_PUBLISH runner line", jobs.get(name, {}).get("runs-on") == RUNS_ON)
# The signed publish job never runs on the self-hosted runner: literal hosted label only.
check("job image runs on the hosted ubuntu-latest runner, written literally",
      jobs.get("image", {}).get("runs-on") == "ubuntu-latest")

image = jobs.get("image", {})
steps = image.get("steps", [])
by_name = {s.get("name", ""): s for s in steps}
check("image job has exactly contents: read, packages: write, id-token: write, attestations: write",
      image.get("permissions") == {"contents": "read", "packages": "write",
                                   "id-token": "write", "attestations": "write"})
check("only the image job gets id-token or attestations",
      all(not ({"id-token", "attestations"} & set(j.get("permissions") or {}))
          for n, j in jobs.items() if n != "image")
      and not ({"id-token", "attestations"} & set(wf.get("permissions") or {})))
names = [s.get("name", "") for s in steps]
ids = {s.get("id"): i for i, s in enumerate(steps) if s.get("id")}
signref = steps[ids["signref"]] if "signref" in ids else {}
check("signing resolves the digest from the build step and refuses latest and non-sha256",
      "build" in ids and "signref" in ids and ids["build"] < ids["signref"]
      and signref.get("if") == "steps.build.outcome == 'success'"
      and "steps.build.outputs.digest" in str(signref.get("env", {}))
      and 'sha256:[0-9a-f]{64}' in signref.get("run", "")
      and '"latest" in tags' in signref.get("run", ""))


def after_signref(name):
    i = names.index(name) if name in names else -1
    return i > ids.get("signref", 10**6) and steps[i].get("if") == "steps.signref.outcome == 'success'"


sign = by_name.get("Sign the image digest (cosign keyless, GitHub OIDC)", {})
check("image is signed by digest with cosign keyless",
      after_signref("Sign the image digest (cosign keyless, GitHub OIDC)")
      and sign.get("run", "").strip() == 'cosign sign --yes "$REF"'
      and sign.get("env", {}).get("REF") == "${{ steps.signref.outputs.repo }}@${{ steps.signref.outputs.digest }}")
for step, action in (("Attest build provenance (pushed to the registry)", "actions/attest-build-provenance@"),
                     ("Attest the SBOM (pushed to the registry)", "actions/attest-sbom@")):
    st = by_name.get(step, {})
    w = st.get("with", {})
    check(f"{step}: {action} on the pushed digest",
          after_signref(step) and str(st.get("uses", "")).startswith(action)
          and w.get("subject-digest") == "${{ steps.signref.outputs.digest }}"
          and w.get("push-to-registry") is True)
note = jobs.get("notify-site", {})
check("notify-site is hosted, has no permissions, runs only for v* tags after the Release",
      note.get("runs-on") == "ubuntu-latest" and note.get("permissions") == {}
      and note.get("if") == "startsWith(github.ref, 'refs/tags/v')"
      and note.get("needs") == ["github-release"])
pre = by_name.get("Preflight, cross-repo package read", {})
check("image job keeps the cross-repo package preflight",
      "PACKAGES_READ_TOKEN" in str(pre.get("env", {})) and "maven.pkg.github.com" in pre.get("run", ""))
refuse = by_name.get("Refuse to overwrite a published version", {})
check("release runs refuse to overwrite a published version",
      refuse.get("if") == "needs.version.outputs.kind == 'release'"
      and "imagetools inspect" in refuse.get("run", ""))

# --- Execute the refuse-to-overwrite step under the shell the runner really uses. ---
# With no `shell:` on the step and no defaults.run.shell, a Linux runner runs the block as
# `bash -e {0}`. `set -uo pipefail` inside it does not turn -e off, so a bare failing
# `out=$(...)` ends the step before its rc is ever looked at.
OLD_PROBE = 'out=$(docker buildx imagetools inspect "${ref}" 2>&1)\nrc=$?'
NEW_PROBE = 'rc=0\nout=$(docker buildx imagetools inspect "${ref}" 2>&1) || rc=$?'
STUB = """#!/bin/sh
printf '%s\\n' "$*" >> "$STUB_LOG"
[ "$1 $2 $3" = "buildx imagetools inspect" ] || { echo "stub: unexpected docker $*" >&2; exit 97; }
case "$STUB_MODE" in
  present) echo "Name: $4"; echo "MediaType: application/vnd.oci.image.index.v1+json"; exit 0 ;;
  absent) echo "ERROR: $4: not found" >&2; exit 1 ;;
  manifest-unknown) echo "ERROR: MANIFEST_UNKNOWN: manifest unknown" >&2; exit 1 ;;
  denied) echo "ERROR: denied: denied" >&2; exit 1 ;;
  *) echo "stub: bad STUB_MODE" >&2; exit 98 ;;
esac
"""
step_shell = (refuse.get("shell") or (image.get("defaults") or {}).get("run", {}).get("shell")
              or (wf.get("defaults") or {}).get("run", {}).get("shell"))
check("refuse-to-overwrite step sets no shell, so the runner uses `bash -e {0}`", step_shell is None)
refuse_run = refuse.get("run", "")
check("refuse-to-overwrite run block has no ${{ }} expressions (inputs arrive via env)",
      "${{" not in refuse_run)
check("refuse-to-overwrite probe uses the bash -e safe form, not a bare out=$(...); rc=$?",
      NEW_PROBE in "\n".join(l.strip() for l in refuse_run.splitlines())
      and "\nrc=$?" not in "\n".join(l.strip() for l in refuse_run.splitlines()))


def run_refuse(body, mode):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        (tmp / "bin").mkdir()
        stub = tmp / "bin" / "docker"
        stub.write_text(STUB, encoding="utf-8")
        stub.chmod(0o755)
        script = tmp / "step.sh"
        script.write_text(body, encoding="utf-8")
        log = tmp / "docker.log"
        env = dict(os.environ, PATH=f"{tmp / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
                   IMAGE="ghcr.io/hugmesh/hugmesh-mesh-bff-api", VERSION="9.9.9-beta.1",
                   STUB_MODE=mode, STUB_LOG=str(log))
        p = subprocess.run(["bash", "-e", str(script)], env=env, capture_output=True, text=True,
                           timeout=30)
        calls = log.read_text(encoding="utf-8") if log.exists() else ""
        return p.returncode, p.stdout + p.stderr, calls


WANT_REF = "buildx imagetools inspect ghcr.io/hugmesh/hugmesh-mesh-bff-api:9.9.9-beta.1\n"
if refuse_run:
    OUTCOMES = ("is not published yet; continuing", "already exists in GHCR", "could not tell whether")
    for mode, want_rc, marker in (("absent", 0, OUTCOMES[0]),
                                  ("manifest-unknown", 0, OUTCOMES[0]),
                                  ("present", 1, OUTCOMES[1]),
                                  ("denied", 1, OUTCOMES[2])):
        rc, out, calls = run_refuse(refuse_run, mode)
        # Exactly one outcome line: the right one, and none of the other two.
        ok = (rc == want_rc and calls == WANT_REF
              and all((m in out) == (m == marker) for m in OUTCOMES))
        check(f"refuse step under bash -e, image {mode}: exit {want_rc} with only '{marker}'", ok)
        if not ok:
            print(f"     got exit {rc}, docker calls {calls!r}, output:\n{out}", file=sys.stderr)
    # The harness must be able to see the defect: the pre-fix form dies on a first publish.
    flat = "\n".join(l.strip() for l in refuse_run.splitlines())
    old_body = flat.replace(NEW_PROBE, OLD_PROBE)
    rc, out, _ = run_refuse(old_body, "absent")
    check("pre-fix probe form, image absent, under bash -e: dies before 'continuing' (harness sees the bug)",
          old_body != flat and rc != 0 and "continuing" not in out)
else:
    check("refuse-to-overwrite step has a run block to execute", False)

build = by_name.get("Build and push image", {}).get("with", {})
check("image tags come only from the version guard",
      build.get("tags") == "${{ needs.version.outputs.tags }}")
check("BuildKit packages_read_token secret is kept",
      "packages_read_token=${{ secrets.PACKAGES_READ_TOKEN }}" in str(build.get("secrets", "")))
check("OCI source label links the package to this repo",
      "org.opencontainers.image.source=https://github.com/${{ github.repository }}" in str(build.get("labels", "")))
code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
check("no literal :latest tag in the workflow (comments aside)", ":latest" not in code)

rel = jobs.get("github-release", {})
check("github-release runs only on a release run",
      rel.get("if") == "needs.version.outputs.kind == 'release'")
check("only github-release gets contents: write", rel.get("permissions") == {"contents": "write"}
      and all("write" not in str(j.get("permissions", {}).get("contents", ""))
              for n, j in jobs.items() if n != "github-release"))
rel_run = "".join(s.get("run", "") for s in rel.get("steps", []))
check("a beta release is prerelease and never latest",
      "--prerelease --latest=false" in rel_run and "--verify-tag" in rel_run)

guard = jobs.get("version", {})
check("version job runs scripts/release-version.sh",
      any("scripts/release-version.sh" in s.get("run", "") for s in guard.get("steps", [])))

if failures:
    print(f"\ntest-release-workflow: {failures} failure(s)", file=sys.stderr)
    sys.exit(1)
print("\ntest-release-workflow: all checks passed")
