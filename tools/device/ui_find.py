"""Find a node in a `uiautomator dump` XML by text or content-description.

Prints "<centerX> <centerY> <label>" for the first match of a case-insensitive regex, or nothing.
Used by the device scripts to tap real UI controls instead of hard-coded screen coordinates, which
break across phones, font sizes and languages.

Usage: python ui_find.py <dump.xml> "<regex>"
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> None:
    pattern = re.compile(sys.argv[2], re.IGNORECASE)
    root = ET.parse(sys.argv[1]).getroot()
    for node in root.iter("node"):
        label = (node.get("text") or "") + " | " + (node.get("content-desc") or "")
        if pattern.search(label):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2, label.strip(" |"))
            return


if __name__ == "__main__":
    main()
