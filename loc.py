from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

# File extensions treated as source code in this project.
DEFAULT_EXTENSIONS = {
    ".py",
    ".ps1",
    ".psm1",
    ".psd1",
    ".js",
    ".ts",
    ".tsx",
    ".jsx",
    ".json",
    ".yml",
    ".yaml",
    ".toml",
    ".ini",
    ".cfg",
    ".md",
    ".sql",
    ".bat",
    ".kt"
}

DEFAULT_EXCLUDE_DIRS = {
    ".git",
    ".venv",
    "venv",
    "__pycache__",
    ".mypy_cache",
    ".pytest_cache",
    "node_modules",
    "dist",
    "build",
    "InstallerOutput",
}

COMMENT_PREFIXES = {
    ".py": "#",
    ".ps1": "#",
    ".psm1": "#",
    ".psd1": "#",
    ".js": "//",
    ".ts": "//",
    ".tsx": "//",
    ".jsx": "//",
    ".sql": "--",
    ".bat": "REM ",
    ".ini": ";",
    ".cfg": ";",
    ".toml": "#",
    ".yml": "#",
    ".yaml": "#",
}


def should_skip(path: Path, root: Path, exclude_dirs: set[str]) -> bool:
    try:
        relative_parts = path.relative_to(root).parts
    except ValueError:
        return True

    return any(part in exclude_dirs for part in relative_parts)


def count_file(path: Path, comment_prefix: str | None) -> tuple[int, int, int]:
    total = 0
    non_empty = 0
    non_comment = 0

    try:
        content = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return 0, 0, 0

    for line in content.splitlines():
        total += 1
        stripped = line.strip()
        if stripped:
            non_empty += 1
            if comment_prefix is None or not stripped.startswith(comment_prefix):
                non_comment += 1

    return total, non_empty, non_comment


def collect_counts(root: Path, extensions: set[str], exclude_dirs: set[str]) -> dict[str, dict[str, int]]:
    counts: dict[str, dict[str, int]] = defaultdict(lambda: {
        "files": 0,
        "total": 0,
        "non_empty": 0,
        "non_comment": 0,
    })

    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if should_skip(path, root, exclude_dirs):
            continue

        suffix = path.suffix.lower()
        if suffix not in extensions:
            continue

        total, non_empty, non_comment = count_file(path, COMMENT_PREFIXES.get(suffix))

        data = counts[suffix]
        data["files"] += 1
        data["total"] += total
        data["non_empty"] += non_empty
        data["non_comment"] += non_comment

    return counts


def print_report(counts: dict[str, dict[str, int]]) -> None:
    if not counts:
        print("No matching files were found.")
        return

    header = f"{'Ext':<8}{'Files':>8}{'Total':>10}{'NonEmpty':>12}{'Code*':>10}"
    print(header)
    print("-" * len(header))

    total_files = 0
    total_lines = 0
    total_non_empty = 0
    total_non_comment = 0

    for ext in sorted(counts):
        item = counts[ext]
        total_files += item["files"]
        total_lines += item["total"]
        total_non_empty += item["non_empty"]
        total_non_comment += item["non_comment"]
        print(
            f"{ext:<8}{item['files']:>8}{item['total']:>10}{item['non_empty']:>12}{item['non_comment']:>10}"
        )

    print("-" * len(header))
    print(
        f"{'TOTAL':<8}{total_files:>8}{total_lines:>10}{total_non_empty:>12}{total_non_comment:>10}"
    )
    print("\n*Code = non-empty lines that do not start with a simple comment prefix.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Count lines of code in a project.")
    parser.add_argument(
        "path",
        nargs="?",
        default=".",
        help="Project root path (default: current directory).",
    )
    parser.add_argument(
        "--ext",
        nargs="+",
        help="Optional custom extensions list, e.g. --ext .py .ps1",
    )
    parser.add_argument(
        "--exclude",
        nargs="+",
        help="Optional directory names to exclude, e.g. --exclude .git venv",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = Path(args.path).resolve()

    if not root.exists() or not root.is_dir():
        raise SystemExit(f"Path is not a directory: {root}")

    extensions = {e.lower() if e.startswith(".") else f".{e.lower()}" for e in (args.ext or DEFAULT_EXTENSIONS)}
    exclude_dirs = set(args.exclude or DEFAULT_EXCLUDE_DIRS)

    counts = collect_counts(root, extensions, exclude_dirs)
    print_report(counts)


if __name__ == "__main__":
    main()
