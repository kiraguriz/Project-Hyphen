#!/usr/bin/env python3
"""Validate roadmap task counts and release-critical evidence layers."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import html
from pathlib import Path
import re
import sys
import unicodedata


TASK_ROW_RE = re.compile(
    r"^[ ]{0,3}\|\s*(HYP-(M[0-6])-[A-Za-z0-9]+)\s*\|\s*([^|]+?)\s*\|"
)
TASK_LIKE_ROW_RE = re.compile(r"^[^|]*\|\s*[^|]*HYP-M[0-6]-[^|]*\|")
SUMMARY_RE = re.compile(
    r"^[ ]{0,3}\|\s*(M[0-6])(?:\s+[^|]*)?\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*$"
)
TASK_ID_LIST_RE = re.compile(
    r"HYP-M[0-6]-[A-Za-z0-9]+(?:\s*,\s*HYP-M[0-6]-[A-Za-z0-9]+)*"
)
ALLOWED_EVIDENCE_MARKERS = {"[x]", "[~]", "[?]", "[ ]", "[-]"}
ALLOWED_TASK_STATUSES = {"[x]", "[~]", "[?]", "[ ]", "[R]"}
EVIDENCE_LAYER_RANKS = {"[ ]": 0, "[?]": 0, "[~]": 1, "[x]": 2}
SUMMARY_START = "<!-- roadmap-summary:start -->"
SUMMARY_END = "<!-- roadmap-summary:end -->"
EVIDENCE_START = "<!-- roadmap-evidence:start -->"
EVIDENCE_END = "<!-- roadmap-evidence:end -->"
ROADMAP_MARKERS = (SUMMARY_START, SUMMARY_END, EVIDENCE_START, EVIDENCE_END)
TABLE_ROW_PREFIX_RE = re.compile(r"^\|")
TOP_LEVEL_TABLE_ROW_PREFIX_RE = re.compile(r"^\|")
TABLE_SEPARATOR_CELL_RE = re.compile(r":?-{3,}:?")
FENCE_OPEN_RE = re.compile(r"^[ ]{0,3}(`{3,}|~{3,})(.*)$")
INDENTED_CODE_RE = re.compile(r"^(?: {4,}|\t)")
RAW_HTML_TOKEN_RE = re.compile(
    r"<!--|-->|<\?|<!\[CDATA\[|</?[A-Za-z][A-Za-z0-9-]*(?=[\s/>]|$)|<![A-Z]"
)
ESCAPED_PIPE_RE = re.compile(r"\\+\|")
NON_GFM_LINE_BOUNDARY_RE = re.compile("[\u000b\u000c\u001c-\u001e\u0085\u2028\u2029]")
REFERENCE_IMAGE_RE = re.compile(r"!\[[^\]]*\](?:[ \t]*\[[^\]]*\])?")
REFERENCE_LINK_RE = re.compile(r"\[([^\]]*)\][ \t]*\[[^\]]*\]")
MARKER_CELL_RE = re.compile(r"`(\[(?:x|~|\?| |R|-)\])`")
CANONICAL_TASK_TABLE_HEADERS = frozenset(
    {
        (
            "ID",
            "Status",
            "Priority",
            "Area",
            "Task",
            "Dependencies",
            "Acceptance criteria",
            "Verification",
        )
    }
)
SUMMARY_HEADER = ["Area", "Done", "In progress", "Blocked", "Remaining"]
EVIDENCE_HEADER = [
    "Journey", "Task IDs", "Implementation", "Automated", "Runtime", "Release", "Evidence / blocker"
]


def _numbered_task_ids(milestone: str, last: int) -> set[str]:
    return {f"HYP-{milestone}-{number:03d}" for number in range(1, last + 1)}


CANONICAL_TASK_IDS = frozenset(
    _numbered_task_ids("M0", 15)
    | _numbered_task_ids("M1", 15)
    | _numbered_task_ids("M2", 16)
    | {"HYP-M2-011b"}
    | _numbered_task_ids("M3", 19)
    | _numbered_task_ids("M4", 12)
    | _numbered_task_ids("M5", 10)
    | _numbered_task_ids("M6", 10)
)
REQUIRED_JOURNEY_TASK_IDS = {
    "Pairing entry and bilateral SAS": frozenset(
        {"HYP-M2-010", "HYP-M2-011", "HYP-M2-011b"}
    ),
    "Persistent connection and recovery": frozenset(
        {"HYP-M2-013", "HYP-M2-016", "HYP-M4-007", "HYP-M6-003"}
    ),
    "Notification mirror, privacy, and actions": frozenset(
        {
            "HYP-M3-001", "HYP-M3-002", "HYP-M3-003", "HYP-M3-004", "HYP-M3-005",
            "HYP-M3-006", "HYP-M3-007", "HYP-M4-008", "HYP-M6-005",
        }
    ),
    "Bidirectional text and links": frozenset({"HYP-M3-008", "HYP-M3-009"}),
    "Transfer queue, delivery, and restart resume": frozenset(
        (_numbered_task_ids("M3", 19) - _numbered_task_ids("M3", 9)) | {"HYP-M6-004"}
    ),
    "Diagnostics preview, export, delete, and opt-in": frozenset(
        _numbered_task_ids("M4", 4)
    ),
    "Accessibility and localization": frozenset(
        {"HYP-M4-005", "HYP-M4-006", "HYP-M4-009"}
    ),
    "Compatibility and reliability matrices": frozenset(
        {"HYP-M4-005", "HYP-M4-006", "HYP-M4-007", "HYP-M4-008", "HYP-M6-003"}
    ),
    "Packaging and distribution": frozenset(
        _numbered_task_ids("M5", 10) | {"HYP-M6-008"}
    ),
    "Evidence beta cohort": frozenset(
        {"HYP-M4-011", "HYP-M4-012", "HYP-M6-001", "HYP-M6-002"}
    ),
    "Release candidate and v1 tag": frozenset({"HYP-M6-009", "HYP-M6-010"}),
}
ALLOWED_NA_LAYERS = {"Evidence beta cohort": frozenset({"automated"})}
RELEASE_CANDIDATE_PREREQUISITES = frozenset(_numbered_task_ids("M6", 8))


class TrackerValidationError(ValueError):
    """Raised when the tracker contradicts its machine-checked summaries."""


@dataclass(frozen=True)
class ValidationResult:
    task_count: int
    evidence_row_count: int


def _is_standalone_marker(line: str, marker: str) -> bool:
    return re.fullmatch(rf"{re.escape(marker)}[ \t]*", line) is not None


def _bounded_block(source: str, start: str, end: str, label: str) -> str:
    lines = source.splitlines()
    start_lines = [index for index, line in enumerate(lines) if _is_standalone_marker(line, start)]
    end_lines = [index for index, line in enumerate(lines) if _is_standalone_marker(line, end)]
    if len(start_lines) != 1 or len(end_lines) != 1:
        raise TrackerValidationError(f"{label} must have exactly one start/end marker pair")
    start_index = start_lines[0]
    end_index = end_lines[0]
    if end_index <= start_index:
        raise TrackerValidationError(f"{label} markers are out of order")
    return "\n".join(lines[start_index + 1 : end_index])


def _mask_html_comments(line: str, in_comment: bool) -> tuple[str, bool]:
    visible: list[str] = []
    position = 0
    while position < len(line):
        if in_comment:
            closing = line.find("-->", position)
            if closing == -1:
                visible.append(" " * (len(line) - position))
                return "".join(visible), True
            visible.append(" " * (closing + 3 - position))
            position = closing + 3
            in_comment = False
            continue

        opening = line.find("<!--", position)
        if opening == -1:
            visible.append(line[position:])
            break
        visible.append(line[position:opening])
        marker = next(
            (candidate for candidate in ROADMAP_MARKERS if line.startswith(candidate, opening)),
            None,
        )
        if marker is not None:
            visible.append(marker)
            position = opening + len(marker)
            continue
        in_comment = True
        position = opening
    return "".join(visible), in_comment


def _is_backslash_escaped(line: str, position: int) -> bool:
    backslashes = 0
    cursor = position - 1
    while cursor >= 0 and line[cursor] == "\\":
        backslashes += 1
        cursor -= 1
    return backslashes % 2 == 1


def _mask_inline_code_spans(line: str) -> str:
    masked = list(line)
    position = 0
    while position < len(line):
        if line[position] != "`" or _is_backslash_escaped(line, position):
            position += 1
            continue
        run_end = position
        while run_end < len(line) and line[run_end] == "`":
            run_end += 1
        run_length = run_end - position
        closing_start: int | None = None
        search_position = run_end
        while search_position < len(line):
            candidate = line.find("`", search_position)
            if candidate == -1:
                break
            candidate_end = candidate
            while candidate_end < len(line) and line[candidate_end] == "`":
                candidate_end += 1
            if candidate_end - candidate == run_length:
                closing_start = candidate
                break
            search_position = candidate_end
        if closing_start is None:
            position = run_end
            continue
        closing_end = closing_start + run_length
        for index in range(position, closing_end):
            masked[index] = " "
        position = closing_end
    return "".join(masked)


def _rendered_tracker_source(source: str) -> tuple[str, list[str]]:
    """Mask Markdown regions that cannot provide rendered task/evidence rows."""

    errors: list[str] = []
    rendered_lines: list[str] = []
    in_html_comment = False
    fence_character = ""
    fence_length = 0

    for line_number, line in enumerate(source.splitlines(), start=1):
        if fence_character:
            if TASK_LIKE_ROW_RE.match(line):
                errors.append(f"line {line_number}: non-rendered task row inside fenced code")
            if any(marker in line for marker in ROADMAP_MARKERS):
                errors.append(f"line {line_number}: non-rendered roadmap marker inside fenced code")
            rendered_lines.append("")
            closing = re.fullmatch(
                rf"[ ]{{0,3}}{re.escape(fence_character)}{{{fence_length},}}[ \t]*",
                line,
            )
            if closing:
                fence_character = ""
                fence_length = 0
            continue

        html_candidate = _mask_inline_code_spans(line)
        for marker in ROADMAP_MARKERS:
            html_candidate = html_candidate.replace(marker, "")
        if RAW_HTML_TOKEN_RE.search(html_candidate):
            errors.append(
                f"line {line_number}: raw HTML is not allowed outside roadmap markers"
            )

        visible_line, next_html_comment = _mask_html_comments(line, in_html_comment)
        if TASK_LIKE_ROW_RE.match(line) and not TASK_LIKE_ROW_RE.match(visible_line):
            errors.append(f"line {line_number}: non-rendered task row inside HTML comment")
        for marker in ROADMAP_MARKERS:
            if marker in line and marker not in visible_line:
                errors.append(f"line {line_number}: non-rendered roadmap marker inside HTML comment")
            elif marker in visible_line and not _is_standalone_marker(visible_line, marker):
                errors.append(f"line {line_number}: roadmap marker must be a standalone line")
        in_html_comment = next_html_comment

        opening = FENCE_OPEN_RE.fullmatch(visible_line)
        if opening and opening.group(1).startswith("`") and "`" in opening.group(2):
            opening = None
        if opening:
            fence = opening.group(1)
            fence_character = fence[0]
            fence_length = len(fence)
            if TASK_LIKE_ROW_RE.match(visible_line):
                errors.append(f"line {line_number}: non-rendered task row inside fenced code")
            if any(marker in visible_line for marker in ROADMAP_MARKERS):
                errors.append(f"line {line_number}: non-rendered roadmap marker inside fenced code")
            rendered_lines.append("")
            continue

        if INDENTED_CODE_RE.match(visible_line):
            for marker in ROADMAP_MARKERS:
                if marker in visible_line:
                    errors.append(f"line {line_number}: non-rendered roadmap marker inside indented code")
                    visible_line = visible_line.replace(marker, " " * len(marker))
        rendered_lines.append(visible_line)

    if fence_character:
        errors.append("unterminated fenced code block")
    if in_html_comment:
        errors.append("unterminated HTML comment")
    return "\n".join(rendered_lines), errors


def _marker(cell: str) -> str:
    match = MARKER_CELL_RE.fullmatch(cell.strip())
    return match.group(1) if match else ""


def _table_cells(line: str) -> list[str]:
    content = line.strip()
    if content.startswith("|"):
        content = content[1:]
    if content.endswith("|"):
        content = content[:-1]
    return [cell.strip() for cell in content.split("|")]


def _has_exact_boundary_pipes(line: str) -> bool:
    content = line.strip()
    return (
        content.startswith("|")
        and not content.startswith("||")
        and content.endswith("|")
        and not content.endswith("||")
    )


def _is_separator(cells: list[str], width: int) -> bool:
    return len(cells) == width and all(TABLE_SEPARATOR_CELL_RE.fullmatch(cell) for cell in cells)


def _inline_destination_end(value: str, label_end: int) -> int | None:
    depth = 1
    cursor = label_end + 2
    while cursor < len(value) and depth:
        if value[cursor] == "\\" and cursor + 1 < len(value):
            cursor += 2
            continue
        if value[cursor] == "(":
            depth += 1
        elif value[cursor] == ")":
            depth -= 1
        cursor += 1
    return cursor if depth == 0 else None


def _strip_markdown_images(value: str) -> str:
    visible: list[str] = []
    position = 0
    while position < len(value):
        image_start = value.find("![", position)
        if image_start == -1:
            visible.append(value[position:])
            break
        visible.append(value[position:image_start])
        label_end = value.find("](", image_start + 2)
        if label_end == -1:
            visible.append(value[image_start])
            position = image_start + 1
            continue
        destination_end = _inline_destination_end(value, label_end)
        if destination_end is None:
            visible.append(value[image_start])
            position = image_start + 1
            continue
        position = destination_end
    return "".join(visible)


def _visible_markdown_text(value: str) -> str:
    value = _strip_markdown_images(value)
    value = REFERENCE_IMAGE_RE.sub("", value)
    value = REFERENCE_LINK_RE.sub(lambda match: match.group(1), value)
    visible: list[str] = []
    position = 0
    while position < len(value):
        label_open = position
        if value[label_open] != "[":
            visible.append(value[position])
            position += 1
            continue
        label_end = value.find("](", label_open + 1)
        if label_end == -1:
            visible.append(value[position])
            position += 1
            continue
        destination_end = _inline_destination_end(value, label_end)
        if destination_end is None:
            visible.append(value[position])
            position += 1
            continue
        visible.append(value[label_open + 1 : label_end])
        position = destination_end
    return html.unescape("".join(visible))


def _visible_alphanumeric_count(value: str) -> int:
    normalized = unicodedata.normalize("NFKC", value)
    return sum(character.isascii() and character.isalnum() for character in normalized)


def _ordered_table_data_lines(
    block: str,
    header: list[str],
    label: str,
    errors: list[str],
) -> list[tuple[int, str]]:
    physical_lines = [
        (line_number, line)
        for line_number, line in enumerate(block.splitlines(), start=1)
    ]
    blank_lines = [line_number for line_number, line in physical_lines if not line.strip()]
    if blank_lines:
        errors.append(f"{label} table blank lines are not allowed: {blank_lines}")
    lines = [(line_number, line) for line_number, line in physical_lines if line.strip()]
    for line_number, line in lines:
        if not _has_exact_boundary_pipes(line):
            errors.append(f"{label} line {line_number}: exactly one boundary pipe is required")
        if ESCAPED_PIPE_RE.search(line):
            errors.append(f"{label} line {line_number}: escaped pipe is not allowed")
    has_ordered_preamble = (
        len(lines) >= 2
        and _has_exact_boundary_pipes(lines[0][1])
        and TABLE_ROW_PREFIX_RE.match(lines[0][1]) is not None
        and _table_cells(lines[0][1]) == header
        and _has_exact_boundary_pipes(lines[1][1])
        and TABLE_ROW_PREFIX_RE.match(lines[1][1]) is not None
        and _is_separator(_table_cells(lines[1][1]), len(header))
    )
    if not has_ordered_preamble:
        errors.append(f"{label} table requires a header followed by separator")
    return lines[2:]


def _canonical_task_table_rows(
    source: str,
    task_table_headers: frozenset[tuple[str, ...]] | set[tuple[str, ...]],
) -> dict[int, int]:
    lines = source.splitlines()
    task_rows: dict[int, int] = {}
    index = 0
    while index + 1 < len(lines):
        line = lines[index]
        if (
            not TOP_LEVEL_TABLE_ROW_PREFIX_RE.match(line)
            or not _has_exact_boundary_pipes(line)
        ):
            index += 1
            continue
        header = _table_cells(line)
        if tuple(header) not in set(task_table_headers):
            index += 1
            continue
        if index > 0 and lines[index - 1].strip():
            index += 1
            continue
        separator_line = lines[index + 1]
        if (
            not TOP_LEVEL_TABLE_ROW_PREFIX_RE.match(separator_line)
            or not _has_exact_boundary_pipes(separator_line)
            or not _is_separator(_table_cells(separator_line), len(header))
        ):
            index += 1
            continue
        row_index = index + 2
        while row_index < len(lines) and TOP_LEVEL_TABLE_ROW_PREFIX_RE.match(lines[row_index]):
            if not _has_exact_boundary_pipes(lines[row_index]):
                break
            if len(_table_cells(lines[row_index])) != len(header):
                break
            task_rows[row_index + 1] = len(header)
            row_index += 1
        index = row_index
    return task_rows


def validate_tracker(
    source: str,
    *,
    required_task_ids: frozenset[str] | set[str] = CANONICAL_TASK_IDS,
    required_journey_task_ids: dict[str, frozenset[str] | set[str]] = REQUIRED_JOURNEY_TASK_IDS,
    allowed_na_layers: dict[str, frozenset[str] | set[str]] = ALLOWED_NA_LAYERS,
    task_table_headers: frozenset[tuple[str, ...]] | set[tuple[str, ...]] = CANONICAL_TASK_TABLE_HEADERS,
) -> ValidationResult:
    unsupported_boundary = NON_GFM_LINE_BOUNDARY_RE.search(source)
    if unsupported_boundary:
        raise TrackerValidationError(
            "non-CR/LF line boundary is not allowed: "
            f"U+{ord(unsupported_boundary.group()):04X}"
        )
    source, errors = _rendered_tracker_source(source)
    task_statuses: dict[str, str] = {}
    milestone_counts: dict[str, Counter[str]] = defaultdict(Counter)
    canonical_task_rows = _canonical_task_table_rows(source, task_table_headers)

    for line_number, line in enumerate(source.splitlines(), start=1):
        match = TASK_ROW_RE.match(line)
        if ESCAPED_PIPE_RE.search(line) and (
            line_number in canonical_task_rows or match or TASK_LIKE_ROW_RE.match(line)
        ):
            errors.append(f"line {line_number}: escaped pipe is not allowed in a task row")
        if not match:
            if line_number in canonical_task_rows or TASK_LIKE_ROW_RE.match(line):
                errors.append(f"line {line_number}: malformed task row")
            continue
        if line_number not in canonical_task_rows:
            errors.append(f"line {line_number}: task row is outside a canonical task table")
            continue
        task_id, milestone, status_cell = match.groups()
        status = _marker(status_cell)
        if status not in ALLOWED_TASK_STATUSES:
            errors.append(f"line {line_number}: invalid task status {status!r} for {task_id}")
            continue
        if task_id in task_statuses:
            errors.append(f"line {line_number}: duplicate task ID {task_id}")
            continue
        task_statuses[task_id] = status
        milestone_counts[milestone][status] += 1

    if not task_statuses:
        errors.append("no HYP-M0..M6 task rows found")
    actual_task_ids = set(task_statuses)
    if actual_task_ids != set(required_task_ids):
        missing = sorted(set(required_task_ids) - actual_task_ids)
        extra = sorted(actual_task_ids - set(required_task_ids))
        errors.append(f"task inventory differs: missing={missing}, extra={extra}")

    try:
        summary_block = _bounded_block(source, SUMMARY_START, SUMMARY_END, "roadmap summary")
    except TrackerValidationError as error:
        errors.append(str(error))
        summary_block = ""

    summary: dict[str, tuple[int, int, int, int]] = {}
    summary_lines = _ordered_table_data_lines(
        summary_block,
        SUMMARY_HEADER,
        "summary",
        errors,
    )
    for line_number, line in summary_lines:
        if not TABLE_ROW_PREFIX_RE.match(line):
            errors.append(f"summary line {line_number}: invalid summary table line")
            continue
        cells = _table_cells(line)
        if cells == SUMMARY_HEADER or _is_separator(cells, len(SUMMARY_HEADER)):
            errors.append(f"summary line {line_number}: invalid summary table line")
            continue
        match = SUMMARY_RE.match(line)
        if not match:
            errors.append(f"summary line {line_number}: invalid summary table line")
            continue
        milestone = match.group(1)
        if milestone in summary:
            errors.append(f"summary line {line_number}: duplicate milestone {milestone}")
            continue
        summary[milestone] = tuple(int(value) for value in match.groups()[1:])
    expected_milestones = set(milestone_counts)
    if set(summary) != expected_milestones:
        missing = sorted(expected_milestones - set(summary))
        extra = sorted(set(summary) - expected_milestones)
        errors.append(f"summary milestones differ: missing={missing}, extra={extra}")

    for milestone, counts in sorted(milestone_counts.items()):
        expected = (
            counts["[x]"],
            counts["[~]"] + counts["[R]"],
            counts["[?]"],
            counts["[ ]"],
        )
        if summary.get(milestone) != expected:
            errors.append(
                f"summary mismatch for {milestone}: rows={expected}, summary={summary.get(milestone)}"
            )

    try:
        evidence_block = _bounded_block(source, EVIDENCE_START, EVIDENCE_END, "evidence ledger")
    except TrackerValidationError as error:
        errors.append(str(error))
        evidence_block = ""

    evidence_rows = 0
    journey_names: set[str] = set()
    evidence_by_journey: dict[str, tuple[str, str, str, str]] = {}
    task_ids_by_journey: dict[str, set[str]] = {}
    evidence_lines = _ordered_table_data_lines(
        evidence_block,
        EVIDENCE_HEADER,
        "evidence",
        errors,
    )
    for line_number, line in evidence_lines:
        if not TABLE_ROW_PREFIX_RE.match(line):
            errors.append(f"evidence line {line_number}: invalid evidence table line")
            continue
        cells = _table_cells(line)
        if cells == EVIDENCE_HEADER or _is_separator(cells, len(EVIDENCE_HEADER)):
            errors.append(f"evidence line {line_number}: invalid evidence table line")
            continue
        if len(cells) != 7:
            errors.append(
                f"evidence line {line_number}: invalid evidence table line; "
                f"expected 7 columns, found {len(cells)}"
            )
            continue

        journey, task_cell, implementation, automated, runtime, release, blocker = cells
        evidence_rows += 1
        if journey in journey_names:
            errors.append(f"evidence line {line_number}: duplicate journey {journey!r}")
        journey_names.add(journey)

        markers = [_marker(value) for value in (implementation, automated, runtime, release)]
        for layer, marker in zip(("implementation", "automated", "runtime", "release"), markers):
            if marker not in ALLOWED_EVIDENCE_MARKERS:
                errors.append(
                    f"evidence line {line_number}: invalid evidence marker {marker!r} for {journey!r}"
                )
            if marker == "[-]" and layer not in set(allowed_na_layers.get(journey, set())):
                errors.append(
                    f"evidence line {line_number}: N/A is not allowed for {journey!r} {layer} evidence"
                )
        prior_rank: int | None = None
        prior_layer = ""
        for layer, marker in zip(
            ("implementation", "automated", "runtime", "release"),
            markers,
        ):
            if marker == "[-]":
                continue
            rank = EVIDENCE_LAYER_RANKS.get(marker)
            if rank is None:
                continue
            if prior_rank is not None and rank > prior_rank:
                errors.append(
                    f"evidence line {line_number}: {layer} cannot advance beyond prior layer "
                    f"{prior_layer} for {journey!r}"
                )
            prior_rank = rank
            prior_layer = layer
        evidence_by_journey[journey] = tuple(markers)

        if not TASK_ID_LIST_RE.fullmatch(task_cell):
            errors.append(f"evidence line {line_number}: malformed task ID list for {journey!r}")
            task_ids: list[str] = []
        else:
            task_ids = [task_id.strip() for task_id in task_cell.split(",")]
        if len(task_ids) != len(set(task_ids)):
            errors.append(f"evidence line {line_number}: duplicate task ID in {journey!r}")
        task_ids_by_journey[journey] = set(task_ids)
        for task_id in task_ids:
            if task_id not in task_statuses:
                errors.append(
                    f"evidence line {line_number}: {journey!r} references unknown task ID {task_id}"
                )

        if markers[-1] == "[x]" and any(marker not in {"[x]", "[-]"} for marker in markers[:-1]):
            errors.append(
                f"evidence line {line_number}: release evidence cannot pass before prior layers for {journey!r}"
            )
        visible_blocker = _visible_markdown_text(blocker)
        if "[?]" in markers and (
            "![" in blocker or _visible_alphanumeric_count(visible_blocker) < 12
        ):
            errors.append(f"evidence line {line_number}: blocked journey {journey!r} needs an actionable blocker")

    if evidence_rows == 0:
        errors.append("evidence ledger has no journey rows")
    required_journeys = set(required_journey_task_ids)
    if journey_names != required_journeys:
        missing = sorted(required_journeys - journey_names)
        extra = sorted(journey_names - required_journeys)
        errors.append(f"evidence journey inventory differs: missing={missing}, extra={extra}")
    for journey, expected_task_ids in required_journey_task_ids.items():
        observed = task_ids_by_journey.get(journey, set())
        if observed != set(expected_task_ids):
            errors.append(
                f"evidence task mapping differs for {journey!r}: "
                f"expected={sorted(expected_task_ids)}, observed={sorted(observed)}"
            )

    release_candidate_done = task_statuses.get("HYP-M6-009") == "[x]"
    tag_done = task_statuses.get("HYP-M6-010") == "[x]"
    if release_candidate_done:
        open_prerequisites = sorted(
            task_id
            for task_id in RELEASE_CANDIDATE_PREREQUISITES
            if task_statuses.get(task_id) != "[x]"
        )
        if open_prerequisites:
            errors.append(
                "HYP-M6-009 cannot be done while prerequisites remain open: "
                f"{open_prerequisites}"
            )
    if tag_done and not release_candidate_done:
        errors.append("HYP-M6-010 cannot be done before HYP-M6-009")
    if release_candidate_done or tag_done:
        for journey in sorted(required_journey_task_ids):
            markers = evidence_by_journey.get(journey)
            release = markers[3] if markers else "missing"
            if release != "[x]":
                errors.append(
                    f"HYP-M6-009/010 cannot be done while {journey!r} release evidence is {release}"
                )

    if errors:
        raise TrackerValidationError("\n".join(errors))
    return ValidationResult(task_count=len(task_statuses), evidence_row_count=evidence_rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "path",
        nargs="?",
        type=Path,
        default=Path("docs/project_hyphen_roadmap_tracker_v0_3.md"),
    )
    args = parser.parse_args(argv)

    try:
        result = validate_tracker(args.path.read_text(encoding="utf-8"))
    except (OSError, TrackerValidationError) as error:
        print(f"roadmap tracker: FAIL\n{error}", file=sys.stderr)
        return 1

    print(
        "roadmap tracker: "
        f"{result.task_count} tasks, {result.evidence_row_count} release-critical journeys, "
        "summary/evidence layers OK"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
