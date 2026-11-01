#!/usr/bin/env python3
"""Check UI text arguments, including multiline calls and custom text composables.

This is a lexical guard, not a Kotlin compiler: dynamic text returned from models
still requires review. Technical formats, protocol values and user data are not
translations. Run tests with: python3 -m unittest discover -s tools/tests -p 'test_check_i18n.py'
"""
import pathlib
import re
import sys

UI_ROOT = "android/app/src/main/kotlin/com/airtak/ui"
CALLS = set("Text TacticalText SectionLabel DropdownSectionLabel ButtonLabel MetricTile StatusPill LabelValue".split())
PARAMS = set("label placeholder title contentDescription text hint secondary prompt labelText summary".split())
TOKEN = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z_0-9]*|[^\s]')
# Exact technical/developer strings; never use a blanket exemption for a screen.
TECHNICAL = {"swipe-delete-color", "Noto Sans Regular", "${it.toInt()} dBm", "DC ${formatDcPercent(dcPercentage)}"}


def tokens(source):
    return [(m.group(), m.start()) for m in TOKEN.finditer(source)
            if not m.group().startswith(("//", "/*"))]


def first_expression(items, start):
    depth = 0
    result = []
    for token, pos in items[start:]:
        if depth == 0 and token in (",", ")", "}"):
            break
        if token in ("(", "{", "["):
            depth += 1
        elif token in (")", "}", "]"):
            depth -= 1
        result.append((token, pos))
    return result


def is_language_literal(token):
    if not token.startswith('"'):
        return False
    value = token.strip('"')
    if value in TECHNICAL:
        return False
    # Interpolation, numeric format strings and units are not English prose.
    value = re.sub(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z_0-9]*|%[0-9.$+-]*[A-Za-z]', '', value)
    return bool(re.search(r'[A-Za-z]{2,}', value))


def scan(source):
    items = tokens(source)
    candidates = set()
    for index, (token, _) in enumerate(items[:-1]):
        following = items[index + 1][0]
        previous = items[index - 1][0] if index else None
        if (token in CALLS and following == "(") or (token in PARAMS and following == "=" and previous not in ("val", "var")):
            for literal, pos in first_expression(items, index + 2):
                if is_language_literal(literal):
                    candidates.add((source.count('\n', 0, pos) + 1, literal))
    return sorted(candidates)


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    ui = root / UI_ROOT
    if not ui.is_dir():
        raise SystemExit(f"not an AirTAK checkout: {ui} does not exist")
    findings = []
    for path in sorted(ui.rglob("*.kt")):
        for line, literal in scan(path.read_text(encoding="utf-8")):
            findings.append(f"{path.relative_to(root)}:{line}: {literal}")
    if findings:
        print(f"{len(findings)} hardcoded UI text literal(s):")
        print('\n'.join(f"  {finding}" for finding in findings))
        print("Move UI text into tools/i18n_strings.py and use stringResource/getString.")
        return 1
    print("no hardcoded UI text literals found (dynamic model text still requires review)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
