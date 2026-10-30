#!/usr/bin/env python3
"""Issue #11 guard: fail when a user-visible string goes back into Kotlin.

Externalising the UI strings is only durable if the next literal is caught. This scans the
UI layer for string literals passed to a composable that renders text, and fails when it
finds one that is not a resource reference.

It is deliberately narrow. It looks at the argument position of composables and
parameters that put text on screen (Text, labels, placeholders, titles, content
descriptions, hints), so it does not complain about log tags, channel ids, MIME types,
date patterns or test tags. Those are not language content, and a guard that cries about
them gets switched off.

Usage:
    python3 tools/check_i18n.py [repo-root]
"""
import pathlib
import re
import sys

# Composable calls and named parameters whose argument is shown to the user.
CALL_SITE = re.compile(
    r'\b(Text|SectionLabel|DropdownSectionLabel|ButtonLabel|MetricTile|TooltipBox|'
    r'TextButton|Button|OutlinedButton|OutlinedTextField|TextField|AlertDialog|'
    r'DropdownMenuItem|AssistChip|FilterChip|Chip|Checkbox|Switch|RadioButton|Tab)\s*\('
    r'|\b(label|placeholder|title|contentDescription|text|hint|secondary|prompt|'
    r'labelText|summary)\s*=\s*"'
)
STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

# Files under the UI layer only. Data, domain and tooling layers legitimately hold
# protocol strings, file names and format patterns.
UI_ROOT = "android/app/src/main/kotlin/com/airtak/ui"

# Lines that are exempt, with the reason. Anything here is a decision, not an oversight:
# a Compose animation label is a developer-facing debug name, never rendered.
EXEMPT = (
    # animateColorAsState(label = ...) is a Compose debug name for the animation, not UI text.
    ('ui/markers/MarkersScreen.kt', 'label = "swipe-delete-color"'),
)


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    ui = root / UI_ROOT
    if not ui.is_dir():
        raise SystemExit(f"not an AirTAK checkout: {ui} does not exist")

    findings = []
    for path in sorted(ui.rglob("*.kt")):
        rel = str(path.relative_to(root))
        for lineno, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
            stripped = line.strip()
            if stripped.startswith(("//", "*", "/*")):
                continue
            if "stringResource" in line or "R.string" in line or "asText()" in line:
                continue
            if not CALL_SITE.search(line):
                continue
            for match in STRING_LITERAL.finditer(line):
                value = match.group(1)
                if not value.strip():
                    continue
                if any(rel.endswith(prefix) and needle in line for prefix, needle in EXEMPT):
                    continue
                findings.append(f"{rel}:{lineno}: {value!r}")

    if findings:
        print(f"{len(findings)} hardcoded user-visible string(s) in the UI layer:")
        for f in findings:
            print(f"  {f}")
        print(
            "\nMove each one into res/values/strings.xml and res/values-de/strings.xml "
            "(see tools/i18n_strings.py) and use stringResource at the call site. "
            "If a literal is genuinely not language content, add it to EXEMPT with a reason."
        )
        return 1
    print("no hardcoded user-visible UI strings found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
