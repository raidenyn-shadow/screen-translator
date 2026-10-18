"""Increment the Android code and selected stable SemVer component."""

import argparse
from pathlib import Path
import re


def bump_version(source, level='patch'):
    if level not in ('major', 'minor', 'patch'):
        raise ValueError('Expected major, minor or patch increment')
    codes = list(re.finditer(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$', source))
    names = list(re.finditer(r'(?m)^(\s*versionName\s*=\s*)"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"(\s*)$', source))
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("Expected exactly one numeric versionCode and one major.minor.patch versionName")
    name = names[0]
    major, minor, patch = map(int, (name[2], name[3], name[4]))
    if level == 'major':
        major, minor, patch = major + 1, 0, 0
    elif level == 'minor':
        minor, patch = minor + 1, 0
    else:
        patch += 1
    version = f"{major}.{minor}.{patch}"
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
    parser.add_argument('--bump', choices=('major', 'minor', 'patch'), default='patch')
    args = parser.parse_args()
    updated, version = bump_version(args.file.read_text(), args.bump)
    args.file.write_text(updated)
    print(version)
