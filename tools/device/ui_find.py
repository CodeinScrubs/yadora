"""Find a node in a `uiautomator dump` XML by text or content-description.

Prints "<centerX> <centerY> <label>" for the first node whose text OR content-description matches a
case-insensitive regex, or nothing. The two attributes are tested separately, so anchors mean what
they say: "^Allow$" finds a button labelled exactly "Allow", not a dialog title that merely starts
with the word, and "^Edit topic$" finds an icon whose only label is its content-description.

Used by the device scripts to tap real UI controls instead of hard-coded screen coordinates, which
break across phones, font sizes and languages.

Usage: python ui_find.py <dump.xml> "<regex>"
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> None:
    # Labels can be Persian; a Windows console or pipe defaults to cp1252 and would crash printing them.
    sys.stdout.reconfigure(encoding="utf-8")
    pattern = re.compile(sys.argv[2], re.IGNORECASE)
    root = ET.parse(sys.argv[1]).getroot()
    for node in root.iter("node"):
        for label in (node.get("text"), node.get("content-desc")):
            if label and pattern.search(label):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                print((x1 + x2) // 2, (y1 + y2) // 2, label)
                return


if __name__ == "__main__":
    main()
