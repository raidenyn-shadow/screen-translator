"""Increment the Android version code and semantic patch version for a release."""

import argparse
from pathlib import Path
import re


def bump_version(source):
    codes = list(re.finditer(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$', source))
    names = list(re.finditer(r'(?m)^(\s*versionName\s*=\s*)"(\d+)\.(\d+)\.(\d+)"(\s*)$', source))
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("Expected exactly one numeric versionCode and one major.minor.patch versionName")
    name = names[0]
    version = f"{name[2]}.{name[3]}.{int(name[4]) + 1}"
    updated = re.sub(
        r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$',
        lambda match: f"{match[1]}{int(match[2]) + 1}{match[3]}",
        source,
    )
    updated = re.sub(
        r'(?m)^(\s*versionName\s*=\s*)"\d+\.\d+\.\d+"(\s*)$',
        lambda match: f'{match[1]}"{version}"{match[2]}',
        updated,
    )
    return updated, version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    updated, version = bump_version(args.file.read_text())
    args.file.write_text(updated)
    print(version)
