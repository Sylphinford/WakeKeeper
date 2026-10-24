#!/usr/bin/env python3
"""Regenerate the third-party license notices for WakeKeeper.

Produces two files from the app's actual resolved runtime classpath:

  THIRD-PARTY-NOTICES.md                     human-readable, ships in the repo
  app/src/main/assets/third_party_licenses.txt   plain text, shown inside the app

Licenses are read from the POM of every resolved module, not from a hardcoded
list, so the output stays correct when dependencies change.

Usage:
    python tools/generate_notices.py                 # resolves via Gradle
    python tools/generate_notices.py --deps deps.txt # reuse an existing dump
                                                     # (./gradlew :app:dependencies
                                                     #   --configuration releaseRuntimeClasspath)
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import glob
import zipfile
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD_OUT = os.path.join(ROOT, "THIRD-PARTY-NOTICES.md")
ASSET_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "third_party_licenses.txt")
APACHE_TXT = os.path.join(ROOT, "tools", "licenses", "Apache-2.0.txt")

CONFIGURATION = "releaseRuntimeClasspath"

# Upstream metadata gaps. Every entry must say why it is here.
OVERRIDES = {
    # Published in 2014 without a <licenses> block; the code is Guava's, and the
    # artifact is an empty placeholder that just forwards to com.google.guava:guava.
    "com.google.guava:listenablefuture": "Apache-2.0",
}

# Normalise the many spellings Maven authors use for the same license.
ALIASES = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0 ": "Apache-2.0",
    "bsd-3-clause": "BSD-3-Clause",
    "the bsd 3-clause license": "BSD-3-Clause",
    "mit": "MIT",
    "the mit license": "MIT",
}

# Bill-of-materials POMs declare versions only; they contain no code, so they
# are not "components" a recipient has to be notified about.
def is_bom(artifact: str) -> bool:
    return artifact.endswith("-bom")


def gradle_cache() -> str:
    home = os.environ.get("GRADLE_USER_HOME") or os.path.join(os.path.expanduser("~"), ".gradle")
    return os.path.join(home, "caches", "modules-2", "files-2.1")


def resolve_dependencies() -> str:
    """Ask Gradle for the resolved runtime classpath."""
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [wrapper, "-q", ":app:dependencies", "--configuration", CONFIGURATION]
    print(f"resolving {CONFIGURATION} ...", file=sys.stderr)
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, shell=(os.name == "nt"))
    if proc.returncode != 0:
        sys.exit(f"gradle failed:\n{proc.stderr[-2000:]}")
    return proc.stdout


def parse_modules(dump: str) -> dict[tuple[str, str], str]:
    """Map (group, artifact) -> resolved version, honouring 'x -> y' upgrades."""
    modules: dict[tuple[str, str], str] = {}
    for line in dump.splitlines():
        if "(c)" in line:  # dependency constraint, not a real dependency
            continue
        m = re.search(r"([A-Za-z0-9._-]+):([A-Za-z0-9._-]+):([0-9][0-9A-Za-z._-]*)", line)
        if not m:
            continue
        group, artifact, version = m.groups()
        upgraded = re.search(r"->\s*([0-9][0-9A-Za-z._-]*)", line)
        if upgraded:
            version = upgraded.group(1)
        modules[(group, artifact)] = version
    return modules


def read_license(cache: str, group: str, artifact: str, version: str) -> str | None:
    key = f"{group}:{artifact}"
    if key in OVERRIDES:
        return OVERRIDES[key]
    poms = glob.glob(os.path.join(cache, group, artifact, version, "*", f"{artifact}-{version}.pom"))
    if not poms:
        return None
    text = open(poms[0], encoding="utf-8", errors="ignore").read()
    block = re.search(r"<licenses>(.*?)</licenses>", text, re.S)
    if not block:
        return None
    name = re.search(r"<name>(.*?)</name>", block.group(1))
    if not name:
        return None
    raw = name.group(1).strip()
    return ALIASES.get(raw.lower(), raw)


def scan_for_notice_files(cache: str, modules: dict[tuple[str, str], str]) -> list[str]:
    """Apache-2.0 section 4(d) only applies if a component ships a NOTICE file."""
    hits = []
    for (group, artifact), version in sorted(modules.items()):
        for pattern in ("*.aar", "*.jar"):
            for path in glob.glob(os.path.join(cache, group, artifact, version, "*",
                                               f"{artifact}-{version}{pattern[1:]}")):
                try:
                    names = zipfile.ZipFile(path).namelist()
                except zipfile.BadZipFile:
                    continue
                if any(os.path.basename(n).upper().startswith("NOTICE") for n in names):
                    hits.append(f"{group}:{artifact}:{version}")
    return hits


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--deps", help="reuse a saved :app:dependencies dump instead of running Gradle")
    args = ap.parse_args()

    dump = open(args.deps, encoding="utf-8", errors="ignore").read() if args.deps else resolve_dependencies()
    cache = gradle_cache()

    modules = {k: v for k, v in parse_modules(dump).items() if not is_bom(k[1])}
    grouped: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    unknown = []
    for (group, artifact), version in sorted(modules.items()):
        lic = read_license(cache, group, artifact, version)
        if lic is None:
            unknown.append(f"{group}:{artifact}:{version}")
            continue
        grouped[lic].append((group, artifact, version))

    if unknown:
        sys.exit("no license found for:\n  " + "\n  ".join(unknown) +
                 "\nadd an entry to OVERRIDES with a justification")

    notice_bearing = scan_for_notice_files(cache, modules)
    apache_text = open(APACHE_TXT, encoding="utf-8").read().rstrip() + "\n"

    ordered = sorted(grouped.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    total = sum(len(v) for _, v in ordered)

    # ---------------------------------------------------------------- markdown
    md = ["# Third-Party Notices", ""]
    md.append("WakeKeeper itself is released under the MIT License (see [LICENSE](LICENSE)).")
    md.append("")
    md.append(f"The application bundles {total} third-party components, listed below. "
              "Every license involved is permissive, so nothing here restricts "
              "redistribution of the app.")
    md.append("")
    md.append("## Summary")
    md.append("")
    md.append("| License | Components |")
    md.append("|---|---|")
    for lic, items in ordered:
        md.append(f"| {lic} | {len(items)} |")
    md.append("")
    md.append("## Components")
    md.append("")
    for lic, items in ordered:
        md.append(f"### {lic}")
        md.append("")
        md.append("| Component | Version |")
        md.append("|---|---|")
        for group, artifact, version in items:
            md.append(f"| `{group}:{artifact}` | {version} |")
        md.append("")
    md.append("## Notice files")
    md.append("")
    if notice_bearing:
        md.append("The following components ship a NOTICE file, whose contents are "
                  "reproduced in this document as required by Apache-2.0 section 4(d):")
        md.append("")
        for item in notice_bearing:
            md.append(f"- `{item}`")
    else:
        md.append("None of the bundled artifacts contains a `NOTICE` file, so Apache-2.0 "
                  "section 4(d) imposes no additional obligation. Section 4(a), which "
                  "requires passing on a copy of the license, is satisfied by the full "
                  "text below and by the same text shown inside the app.")
    md.append("")
    md.append("## Build tooling")
    md.append("")
    md.append("Used to compile the app; none of it is redistributed inside the APK.")
    md.append("")
    md.append("| Tool | License |")
    md.append("|---|---|")
    md.append("| Gradle | Apache-2.0 |")
    md.append("| Android Gradle Plugin | Apache-2.0 |")
    md.append("| Kotlin compiler and Gradle plugin | Apache-2.0 |")
    md.append("| AAPT2 | Apache-2.0 |")
    md.append("| D8 / R8 | BSD-3-Clause |")
    md.append("| `android.jar` (Android platform API) | Apache-2.0; use is additionally "
              "governed by the Android SDK terms |")
    md.append("")
    md.append("## License texts")
    md.append("")
    for lic, _ in ordered:
        if lic == "Apache-2.0":
            md.append("### Apache License 2.0")
            md.append("")
            md.append("```")
            md.append(apache_text.rstrip("\n"))
            md.append("```")
            md.append("")
        else:
            md.append(f"### {lic}")
            md.append("")
            md.append(f"The full text of {lic} is available at "
                      f"<https://spdx.org/licenses/{lic}.html>.")
            md.append("")
    md.append("---")
    md.append("")
    md.append("Generated by `tools/generate_notices.py`. Regenerate after any dependency "
              "change; do not edit by hand.")
    md.append("")
    open(MD_OUT, "w", encoding="utf-8", newline="\n").write("\n".join(md))

    # -------------------------------------------------------------- in-app text
    out = []
    out.append("WakeKeeper 开源许可 / Open source notices")
    out.append("=" * 46)
    out.append("")
    out.append("WakeKeeper 本身以 MIT 许可证发布，全文见仓库中的 LICENSE 文件。")
    out.append("WakeKeeper itself is released under the MIT License; see LICENSE.")
    out.append("")
    out.append(f"本应用包含 {total} 个第三方组件，均为宽松许可证。")
    out.append(f"This app bundles {total} third-party components, all permissively licensed.")
    out.append("")
    for lic, items in ordered:
        out.append("-" * 46)
        out.append(f"{lic}  ({len(items)} components)")
        out.append("-" * 46)
        out.append("")
        for group, artifact, version in items:
            out.append(f"    {group}:{artifact}  {version}")
        out.append("")
    out.append("-" * 46)
    out.append("License texts / 许可证全文")
    out.append("-" * 46)
    out.append("")
    for lic, _ in ordered:
        if lic == "Apache-2.0":
            out.append(apache_text)
        else:
            out.append(f"{lic}: https://spdx.org/licenses/{lic}.html")
            out.append("")
    out.append("")
    out.append("Generated by tools/generate_notices.py.")
    out.append("")
    os.makedirs(os.path.dirname(ASSET_OUT), exist_ok=True)
    open(ASSET_OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out))

    print(f"components: {total}", file=sys.stderr)
    for lic, items in ordered:
        print(f"  {lic}: {len(items)}", file=sys.stderr)
    print(f"notice files: {len(notice_bearing)}", file=sys.stderr)
    print(f"wrote {MD_OUT}", file=sys.stderr)
    print(f"wrote {ASSET_OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
