#!/usr/bin/env python3
"""
Insert HavocFolia's hooks into the generated Minecraft sources.

Why this exists
---------------
Patch files under minecraft-patches/sources carry real line numbers
(`@@ -1325,11 +_,7 @@`). They cannot be hand-written — the line numbers only
exist once Minecraft has been decompiled and remapped. The supported flow is:

    ./gradlew applyAllPatches      # generates havocfolia-server/src/minecraft/...
    python3 scripts/apply-hooks.py # edits those sources in place
    ./gradlew rebuildAllServerPatches  # writes correctly-anchored patch files

After the first successful run the patches are committed and this script becomes
a no-op — it detects its own marker comments and skips.

It anchors on searchable strings rather than line numbers, so an upstream bump
that shifts lines around does not break it. An upstream bump that *renames* the
anchor does, and it will tell you exactly which one.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

MARKER = "HavocFolia start"


@dataclass
class Hook:
    name: str
    # File name to locate under the generated minecraft sources.
    filename: str
    # Substring that must appear in the path, to disambiguate same-named classes.
    path_hint: str
    # Unique line to anchor on.
    anchor: str
    # "after" inserts following the anchor line, "before" precedes it.
    position: str
    # Lines to insert (indentation included).
    body: list[str]
    note: str = ""


HOOKS: list[Hook] = [
    Hook(
        name="bootstrap",
        filename="DedicatedServer.java",
        path_hint="net/minecraft/server/dedicated",
        anchor="public boolean initServer()",
        position="after",
        body=[
            "        // HavocFolia start - fork bootstrap",
            "        gg.havoc.folia.HavocFolia.bootstrap(this.getServerDirectory());",
            "        // HavocFolia end",
        ],
        note="Must run before any world loads; ServerLevel's constructor reads this config.",
    ),
    Hook(
        name="antifreecam",
        filename="ServerLevel.java",
        path_hint="net/minecraft/server/level",
        anchor="this.chunkPacketBlockController =",
        position="before",
        body=[
            "        // HavocFolia start - native AntiFreecam",
            "        if (gg.havoc.folia.config.HavocConfig.get().antiFreecam.enabled) {",
            "            this.chunkPacketBlockController = gg.havoc.folia.HavocFolia.createController(this);",
            "        } else",
            "        // HavocFolia end",
        ],
        note="Turns the following assignment into the else branch. Check the result compiles; "
             "if upstream assigns in a different shape, wire it by hand.",
    ),
    Hook(
        name="scheduler",
        filename="TickRegionScheduler.java",
        path_hint="threadedregions",
        anchor="threadPool.start()",
        position="after",
        body=[
            "        // HavocFolia start - pin region workers once their threads exist",
            "        gg.havoc.folia.HavocFolia.onSchedulerStart();",
            "        // HavocFolia end",
        ],
        note="Must run after the worker threads exist; pinning cannot find a thread that has "
             "not started.",
    ),
    Hook(
        name="commands",
        filename="Commands.java",
        path_hint="net/minecraft/commands",
        anchor="PublishCommand.register(this.dispatcher)",
        position="after",
        body=[
            "        // HavocFolia start - fork admin command set",
            "        gg.havoc.folia.command.HavocCommand.register(this.dispatcher);",
            "        // HavocFolia end",
        ],
        note="Must come after vanilla registrations or the node gets overwritten.",
    ),
    Hook(
        name="viewdistance",
        filename="ServerPlayer.java",
        path_hint="net/minecraft/server/level",
        anchor="public void tick()",
        position="after",
        body=[
            "        // HavocFolia start - adaptive view distance from this region's tick time",
            "        gg.havoc.folia.view.AdaptiveViewDistance.applyIfDue(this);",
            "        // HavocFolia end",
        ],
        note="Must be on a region thread; RegionMspt returns 0 off-thread, which reads as "
             "'no pressure' and silently disables the feature.",
    ),
]


def find_source_root(repo: Path) -> Path | None:
    """Locate the generated Minecraft sources produced by applyAllPatches."""
    for candidate in [
        repo / "havocfolia-server" / "src" / "minecraft" / "java",
        repo / "havocfolia-server" / "src" / "minecraft",
    ]:
        if candidate.is_dir():
            return candidate
    return None


def locate(root: Path, hook: Hook) -> Path | None:
    matches = [
        p for p in root.rglob(hook.filename)
        if hook.path_hint.replace("/", "") in str(p.parent).replace("/", "")
    ]
    if not matches:
        matches = list(root.rglob(hook.filename))
    return matches[0] if len(matches) == 1 else (matches[0] if matches else None)


def apply(hook: Hook, path: Path, check_only: bool) -> str:
    text = path.read_text(encoding="utf-8")

    if MARKER in text and any(b.strip() in text for b in hook.body if b.strip().startswith("gg.havoc")):
        return "skip"

    lines = text.splitlines()
    target = None
    for i, line in enumerate(lines):
        if hook.anchor in line:
            target = i
            break
    if target is None:
        return "anchor-missing"
    if check_only:
        return "would-apply"

    at = target + 1 if hook.position == "after" else target
    lines[at:at] = hook.body
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return "applied"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true",
                    help="report what would happen without editing anything")
    ap.add_argument("--repo", default=".", help="repository root (default: cwd)")
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    root = find_source_root(repo)
    if root is None:
        print("error: generated Minecraft sources not found.", file=sys.stderr)
        print("       Run './gradlew applyAllPatches' first.", file=sys.stderr)
        return 2

    print(f"Sources: {root.relative_to(repo)}\n")

    failures = 0
    for hook in HOOKS:
        path = locate(root, hook)
        if path is None:
            print(f"  MISSING FILE   {hook.name:14s} {hook.filename}")
            print(f"                 upstream may have moved or renamed it")
            failures += 1
            continue

        status = apply(hook, path, args.check)
        rel = path.relative_to(root)
        if status == "applied":
            print(f"  applied        {hook.name:14s} {rel}")
        elif status == "would-apply":
            print(f"  would apply    {hook.name:14s} {rel}")
        elif status == "skip":
            print(f"  already there  {hook.name:14s} {rel}")
        else:
            print(f"  ANCHOR MISSING {hook.name:14s} {rel}")
            print(f"                 looked for: {hook.anchor!r}")
            print(f"                 {hook.note}")
            failures += 1

    print()
    if failures:
        print(f"{failures} hook(s) need wiring by hand — see docs/HOOKS.md.")
        print("Edit the file above, then run: ./gradlew rebuildAllServerPatches")
        return 1

    print("All hooks in place. Now run:  ./gradlew rebuildAllServerPatches")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
