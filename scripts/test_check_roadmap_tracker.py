#!/usr/bin/env python3
"""Regression tests for the roadmap count and evidence-layer validator."""

import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

from check_roadmap_tracker import TrackerValidationError, validate_tracker


VALID_TRACKER = """\
## Workstream M0

| ID | Status | Priority |
|---|---|---|
| HYP-M0-001 | `[x]` | P0 |
| HYP-M0-002 | `[~]` | P0 |
| HYP-M0-003 | `[?]` | P0 |
| HYP-M0-004 | `[ ]` | P1 |

<!-- roadmap-summary:start -->
| Area | Done | In progress | Blocked | Remaining |
|---|---:|---:|---:|---:|
| M0 Test | 1 | 1 | 1 | 1 |
<!-- roadmap-summary:end -->

<!-- roadmap-evidence:start -->
| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |
|---|---|---|---|---|---|---|
| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open. |
<!-- roadmap-evidence:end -->
"""

FIXTURE_TASK_IDS = {"HYP-M0-001", "HYP-M0-002", "HYP-M0-003", "HYP-M0-004"}
FIXTURE_JOURNEY_TASK_IDS = {"Pairing": {"HYP-M0-001"}}
FIXTURE_TASK_TABLE_HEADERS = {("ID", "Status", "Priority")}


def validate_fixture(source: str):
    return validate_tracker(
        source,
        required_task_ids=FIXTURE_TASK_IDS,
        required_journey_task_ids=FIXTURE_JOURNEY_TASK_IDS,
        allowed_na_layers={},
        task_table_headers=FIXTURE_TASK_TABLE_HEADERS,
    )


def tracker_with_m6(statuses: dict[str, str]) -> str:
    rows = "".join(f"| {task_id} | `{status}` | P0 |\n" for task_id, status in statuses.items())
    counts = {
        "done": sum(status == "[x]" for status in statuses.values()),
        "progress": sum(status in {"[~]", "[R]"} for status in statuses.values()),
        "blocked": sum(status == "[?]" for status in statuses.values()),
        "remaining": sum(status == "[ ]" for status in statuses.values()),
    }
    source = VALID_TRACKER.replace(
        "| HYP-M0-004 | `[ ]` | P1 |",
        "| HYP-M0-004 | `[ ]` | P1 |\n" + rows.rstrip(),
    ).replace(
        "| M0 Test | 1 | 1 | 1 | 1 |",
        "| M0 Test | 1 | 1 | 1 | 1 |\n"
        f"| M6 Test | {counts['done']} | {counts['progress']} | "
        f"{counts['blocked']} | {counts['remaining']} |",
    )
    return source.replace(
        "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open. |",
        "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[x]` | `[x]` | Exact release evidence passed. |",
    )


class RoadmapTrackerValidationTests(unittest.TestCase):
    def test_accepts_exact_counts_and_layered_evidence(self) -> None:
        result = validate_fixture(VALID_TRACKER)

        self.assertEqual(result.task_count, 4)
        self.assertEqual(result.evidence_row_count, 1)

    def test_rejects_non_gfm_line_boundary_characters(self) -> None:
        separators = ("\u000b", "\u000c", "\u001c", "\u001d", "\u001e", "\u0085", "\u2028", "\u2029")
        for separator in separators:
            with self.subTest(codepoint=f"U+{ord(separator):04X}"):
                with self.assertRaisesRegex(TrackerValidationError, "non-CR/LF line boundary"):
                    validate_fixture(VALID_TRACKER.replace("\n", separator))

    def test_real_tracker_rejects_unicode_line_separator_rewrite(self) -> None:
        tracker_path = (
            Path(__file__).resolve().parents[1]
            / "docs"
            / "project_hyphen_roadmap_tracker_v0_3.md"
        )
        rewritten = tracker_path.read_text(encoding="utf-8").replace("\n", "\u2028")

        with self.assertRaisesRegex(TrackerValidationError, "non-CR/LF line boundary"):
            validate_tracker(rewritten)

    def test_rejects_summary_count_drift(self) -> None:
        broken = VALID_TRACKER.replace("| M0 Test | 1 | 1 | 1 | 1 |", "| M0 Test | 2 | 1 | 1 | 0 |")

        with self.assertRaisesRegex(TrackerValidationError, "summary mismatch"):
            validate_fixture(broken)

    def test_summary_rows_must_remain_top_level(self) -> None:
        indented = VALID_TRACKER.replace("| M0 Test |", "   | M0 Test |")

        with self.assertRaisesRegex(TrackerValidationError, "invalid summary table line"):
            validate_fixture(indented)

    def test_summary_rejects_extra_or_malformed_content(self) -> None:
        mutations = (
            "| Overall | 1 | 1 | 1 | 1 |",
            "| M0 Test | 999 | 0 | 0 |",
            "| this summary is verified |",
        )
        for extra in mutations:
            with self.subTest(extra=extra):
                broken = VALID_TRACKER.replace(
                    "<!-- roadmap-summary:end -->",
                    f"{extra}\n<!-- roadmap-summary:end -->",
                )
                with self.assertRaisesRegex(TrackerValidationError, "invalid summary table line"):
                    validate_fixture(broken)

    def test_rejects_duplicate_task_ids(self) -> None:
        broken = VALID_TRACKER.replace(
            "| HYP-M0-002 | `[~]` | P0 |",
            "| HYP-M0-001 | `[~]` | P0 |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "duplicate task ID"):
            validate_fixture(broken)

    def test_rejects_invalid_task_status(self) -> None:
        broken = VALID_TRACKER.replace("| HYP-M0-002 | `[~]` |", "| HYP-M0-002 | `[maybe]` |")

        with self.assertRaisesRegex(TrackerValidationError, "invalid task status"):
            validate_fixture(broken)

    def test_rejects_malformed_task_and_evidence_marker_code_spans(self) -> None:
        mutations = (
            ("| HYP-M0-001 | `[x]` |", "| HYP-M0-001 | ``[x]` |"),
            (
                "| Pairing | HYP-M0-001 | `[x]` | `[x]` |",
                "| Pairing | HYP-M0-001 | ``[x]` | `[x]` |",
            ),
        )
        for original, replacement in mutations:
            with self.subTest(replacement=replacement):
                with self.assertRaisesRegex(TrackerValidationError, "invalid .* marker|invalid task status"):
                    validate_fixture(VALID_TRACKER.replace(original, replacement))

    def test_rejects_invalid_evidence_marker(self) -> None:
        broken = VALID_TRACKER.replace(
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` |",
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[maybe]` | `[?]` |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "invalid evidence marker"):
            validate_fixture(broken)

    def test_release_pass_requires_prior_layers(self) -> None:
        broken = VALID_TRACKER.replace(
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` |",
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[x]` |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "release evidence cannot pass"):
            validate_fixture(broken)

    def test_each_evidence_layer_cannot_advance_beyond_prior_layers(self) -> None:
        mutations = (
            "| Pairing | HYP-M0-001 | `[?]` | `[x]` | `[x]` | `[?]` |",
            "| Pairing | HYP-M0-001 | `[x]` | `[?]` | `[x]` | `[?]` |",
        )
        original = "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` |"
        for replacement in mutations:
            with self.subTest(replacement=replacement):
                with self.assertRaisesRegex(TrackerValidationError, "cannot advance beyond prior layer"):
                    validate_fixture(VALID_TRACKER.replace(original, replacement))

    def test_evidence_task_references_must_exist(self) -> None:
        broken = VALID_TRACKER.replace("HYP-M0-001 | `[x]` | `[x]`", "HYP-M0-999 | `[x]` | `[x]`", 1)

        with self.assertRaisesRegex(TrackerValidationError, "unknown task ID"):
            validate_fixture(broken)

    def test_evidence_task_cell_must_be_a_plain_comma_separated_list(self) -> None:
        for malformed in (
            "HYP-M0-001??",
            "[HYP-M0-001](#x)",
            "`HYP-M0-001`",
        ):
            with self.subTest(malformed=malformed):
                broken = VALID_TRACKER.replace(
                    "| Pairing | HYP-M0-001 |",
                    f"| Pairing | {malformed} |",
                )
                with self.assertRaisesRegex(TrackerValidationError, "malformed task ID list"):
                    validate_fixture(broken)

    def test_journey_cannot_swap_to_an_unrelated_known_task(self) -> None:
        broken = VALID_TRACKER.replace("| Pairing | HYP-M0-001 |", "| Pairing | HYP-M0-002 |")

        with self.assertRaisesRegex(TrackerValidationError, "task mapping differs"):
            validate_fixture(broken)

    def test_task_rows_must_remain_top_level(self) -> None:
        indented = VALID_TRACKER.replace("| HYP-M0-004", " | HYP-M0-004")

        with self.assertRaisesRegex(TrackerValidationError, "outside a canonical task table"):
            validate_fixture(indented)

    def test_list_child_task_table_is_not_canonical(self) -> None:
        task_block = """\
| ID | Status | Priority |
|---|---|---|
| HYP-M0-001 | `[x]` | P0 |
| HYP-M0-002 | `[~]` | P0 |
| HYP-M0-003 | `[?]` | P0 |
| HYP-M0-004 | `[ ]` | P1 |"""
        nested = "- wrapper list item\n" + "\n".join(
            "  " + line for line in task_block.splitlines()
        )
        broken = VALID_TRACKER.replace(task_block, nested)

        with self.assertRaisesRegex(TrackerValidationError, "outside a canonical task table"):
            validate_fixture(broken)

    def test_task_table_cannot_be_a_lazy_block_continuation(self) -> None:
        header = "| ID | Status | Priority |"
        wrappers = ("paragraph wrapper", "> wrapper", "- wrapper", "1. wrapper")
        for wrapper in wrappers:
            with self.subTest(wrapper=wrapper):
                broken = VALID_TRACKER.replace(header, f"{wrapper}\n{header}")
                with self.assertRaisesRegex(TrackerValidationError, "outside a canonical task table"):
                    validate_fixture(broken)

    def test_task_table_rejects_escaped_pipe_column_ambiguity(self) -> None:
        ambiguous = r"""\
## Workstream M0

| ID | Status | Priority | Area |
|---|---|---|---|
| HYP-M0-001 | `[x]` | P0 \| Product |

<!-- roadmap-summary:start -->
| Area | Done | In progress | Blocked | Remaining |
|---|---:|---:|---:|---:|
| M0 Test | 1 | 0 | 0 | 0 |
<!-- roadmap-summary:end -->

<!-- roadmap-evidence:start -->
| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |
|---|---|---|---|---|---|---|
| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open. |
<!-- roadmap-evidence:end -->
"""

        with self.assertRaisesRegex(TrackerValidationError, "escaped pipe"):
            validate_tracker(
                ambiguous,
                required_task_ids={"HYP-M0-001"},
                required_journey_task_ids={"Pairing": {"HYP-M0-001"}},
                allowed_na_layers={},
                task_table_headers={("ID", "Status", "Priority", "Area")},
            )

    def test_machine_tables_reject_repeated_boundary_pipes(self) -> None:
        mutations = (
            ("| ID | Status | Priority |", "|| ID | Status | Priority |"),
            (
                "| Area | Done | In progress | Blocked | Remaining |",
                "| Area | Done | In progress | Blocked | Remaining |||",
            ),
            (
                "| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |",
                "|| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |",
            ),
        )
        for original, replacement in mutations:
            with self.subTest(replacement=replacement):
                with self.assertRaises(TrackerValidationError):
                    validate_fixture(VALID_TRACKER.replace(original, replacement))

    def test_machine_tables_require_exact_single_boundary_pipes(self) -> None:
        mutations = (
            ("| ID | Status | Priority |", "| ID | Status | Priority"),
            ("| HYP-M0-001 | `[x]` | P0 |", "| HYP-M0-001 | `[x]` | P0"),
            (
                "| Area | Done | In progress | Blocked | Remaining |",
                "| Area | Done | In progress | Blocked | Remaining",
            ),
            (
                "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open. |",
                "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open.",
            ),
        )
        for original, replacement in mutations:
            with self.subTest(replacement=replacement):
                with self.assertRaises(TrackerValidationError):
                    validate_fixture(VALID_TRACKER.replace(original, replacement))

    def test_task_rows_require_a_rendered_gfm_table_preamble(self) -> None:
        broken = VALID_TRACKER.replace(
            "| ID | Status | Priority |\n|---|---|---|\n",
            "",
        )

        with self.assertRaisesRegex(TrackerValidationError, "outside a canonical task table"):
            validate_fixture(broken)

    def test_blockquote_task_table_cannot_escape_inventory(self) -> None:
        extra = """\
> | ID | Status | Priority |
> |---|---|---|
> | HYP-M0-999 | `[x]` | P0 |
"""
        broken = VALID_TRACKER + extra

        with self.assertRaisesRegex(TrackerValidationError, "malformed task row"):
            validate_fixture(broken)

    def test_canonical_task_table_rejects_unparsed_data_rows(self) -> None:
        broken = VALID_TRACKER.replace(
            "| HYP-M0-004 | `[ ]` | P1 |",
            "| HYP-M0-004 | `[ ]` | P1 |\n| visually-extra-task | `[x]` | P0 |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "malformed task row"):
            validate_fixture(broken)

    def test_task_like_row_cannot_hide_in_code_block_indentation(self) -> None:
        for prefix in ("    ", "\t"):
            with self.subTest(prefix=repr(prefix)):
                broken = VALID_TRACKER.replace(
                    "<!-- roadmap-summary:start -->",
                    f"{prefix}| HYP-M0-999 | `[x]` | P0 |\n\n"
                    "<!-- roadmap-summary:start -->",
                )
                with self.assertRaisesRegex(TrackerValidationError, "malformed task row"):
                    validate_fixture(broken)

    def test_task_row_cannot_hide_in_fence_or_html_comment(self) -> None:
        row = "| HYP-M0-004 | `[ ]` | P1 |"
        wrappers = (
            f"```text\n{row}\n```",
            f"~~~text\n{row}\n~~~",
            f"<!--\n{row}\n-->",
        )
        for wrapped in wrappers:
            with self.subTest(wrapper=wrapped.splitlines()[0]):
                broken = VALID_TRACKER.replace(row, wrapped)
                with self.assertRaisesRegex(TrackerValidationError, "non-rendered task row"):
                    validate_fixture(broken)

    def test_invalid_backtick_fence_info_cannot_hide_machine_tables(self) -> None:
        invalid_prefix = "```bad`info\n<div>\n```\n"
        targets = (
            "| ID | Status | Priority |",
            "<!-- roadmap-summary:start -->",
            "<!-- roadmap-evidence:start -->",
        )
        for target in targets:
            with self.subTest(target=target):
                broken = VALID_TRACKER.replace(target, invalid_prefix + target)
                with self.assertRaises(TrackerValidationError):
                    validate_fixture(broken)

    def test_rejects_missing_canonical_task(self) -> None:
        broken = VALID_TRACKER.replace("| HYP-M0-004 | `[ ]` | P1 |\n", "").replace(
            "| M0 Test | 1 | 1 | 1 | 1 |",
            "| M0 Test | 1 | 1 | 1 | 0 |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "task inventory differs"):
            validate_fixture(broken)

    def test_rejects_missing_required_journey(self) -> None:
        broken = VALID_TRACKER.replace(
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` | Device run is open. |\n",
            "",
        )

        with self.assertRaisesRegex(TrackerValidationError, "evidence journey inventory differs"):
            validate_fixture(broken)

    def test_evidence_rejects_code_block_indentation(self) -> None:
        broken = VALID_TRACKER.replace("| Pairing |", "    | Pairing |")

        with self.assertRaisesRegex(TrackerValidationError, "invalid evidence table line"):
            validate_fixture(broken)

    def test_marker_block_cannot_hide_in_fence_or_html_comment(self) -> None:
        start = "<!-- roadmap-evidence:start -->"
        end = "<!-- roadmap-evidence:end -->"
        wrappers = (
            ("```text\n", "\n```"),
            ("~~~text\n", "\n~~~"),
            ("<!--\n", "\n-->"),
        )
        for prefix, suffix in wrappers:
            with self.subTest(wrapper=prefix.strip()):
                broken = VALID_TRACKER.replace(start, prefix + start).replace(end, end + suffix)
                with self.assertRaisesRegex(TrackerValidationError, "non-rendered roadmap marker"):
                    validate_fixture(broken)

    def test_raw_html_cannot_wrap_tasks_or_machine_checked_blocks(self) -> None:
        task_row = "| HYP-M0-004 | `[ ]` | P1 |"
        blocks = (
            (task_row, task_row),
            ("<!-- roadmap-summary:start -->", "<!-- roadmap-summary:end -->"),
            ("<!-- roadmap-evidence:start -->", "<!-- roadmap-evidence:end -->"),
        )
        for tag in ("pre", "div", "details"):
            for start, end in blocks:
                with self.subTest(tag=tag, block=start):
                    broken = VALID_TRACKER.replace(start, f"<{tag}>\n{start}").replace(
                        end,
                        f"{end}\n</{tag}>",
                    )
                    with self.assertRaisesRegex(TrackerValidationError, "raw HTML is not allowed"):
                        validate_fixture(broken)

    def test_raw_html_tokens_inside_inline_code_are_allowed(self) -> None:
        documented = VALID_TRACKER.replace(
            "## Workstream M0",
            "## Workstream M0\n\nUse `<nonce>\\` and ``<device-id>`` placeholders.",
        )

        result = validate_fixture(documented)

        self.assertEqual(result.task_count, 4)

    def test_escaped_backticks_do_not_hide_raw_html(self) -> None:
        prose_mutations = (
            r"Escaped opener: \`<div>`",
            r"Escaped comment opener: \`<!-- raw -->`",
            r"Early single close: `safe \` <span hidden>text</span>`",
            r"Early double close: ``safe \`` <span hidden>text</span>``",
        )
        for prose in prose_mutations:
            with self.subTest(prose=prose):
                broken = VALID_TRACKER.replace("## Workstream M0", f"## Workstream M0\n\n{prose}")
                with self.assertRaisesRegex(TrackerValidationError, "raw HTML is not allowed"):
                    validate_fixture(broken)

    def test_evidence_rejects_extra_non_table_content(self) -> None:
        broken = VALID_TRACKER.replace(
            "<!-- roadmap-evidence:end -->",
            "this ledger is complete\n<!-- roadmap-evidence:end -->",
        )

        with self.assertRaisesRegex(TrackerValidationError, "invalid evidence table line"):
            validate_fixture(broken)

    def test_summary_and_evidence_require_header_then_separator(self) -> None:
        table_pairs = (
            (
                "| Area | Done | In progress | Blocked | Remaining |",
                "|---|---:|---:|---:|---:|",
            ),
            (
                "| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |",
                "|---|---|---|---|---|---|---|",
            ),
        )
        for header, separator in table_pairs:
            with self.subTest(header=header):
                broken = (
                    VALID_TRACKER.replace(header, "__HEADER__", 1)
                    .replace(separator, header, 1)
                    .replace("__HEADER__", separator, 1)
                )
                with self.assertRaisesRegex(TrackerValidationError, "header followed by separator"):
                    validate_fixture(broken)

    def test_summary_and_evidence_rows_must_be_physically_contiguous(self) -> None:
        replacements = (
            (
                "| Area | Done | In progress | Blocked | Remaining |\n|---|---:|---:|---:|---:|",
                "| Area | Done | In progress | Blocked | Remaining |\n\n|---|---:|---:|---:|---:|",
            ),
            (
                "|---|---:|---:|---:|---:|\n| M0 Test",
                "|---|---:|---:|---:|---:|\n\n| M0 Test",
            ),
            (
                "| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |\n|---|---|---|---|---|---|---|",
                "| Journey | Task IDs | Implementation | Automated | Runtime | Release | Evidence / blocker |\n\n|---|---|---|---|---|---|---|",
            ),
            (
                "|---|---|---|---|---|---|---|\n| Pairing |",
                "|---|---|---|---|---|---|---|\n\n| Pairing |",
            ),
        )
        for original, replacement in replacements:
            with self.subTest(replacement=replacement):
                with self.assertRaisesRegex(TrackerValidationError, "blank lines are not allowed"):
                    validate_fixture(VALID_TRACKER.replace(original, replacement))

    def test_summary_and_evidence_cannot_be_list_children(self) -> None:
        marker_pairs = (
            ("<!-- roadmap-summary:start -->", "<!-- roadmap-summary:end -->"),
            ("<!-- roadmap-evidence:start -->", "<!-- roadmap-evidence:end -->"),
        )
        for start, end in marker_pairs:
            with self.subTest(start=start):
                block_start = VALID_TRACKER.index(start)
                block_end = VALID_TRACKER.index(end) + len(end)
                block = VALID_TRACKER[block_start:block_end]
                nested = "- wrapper\n" + "\n".join(
                    "  " + line for line in block.splitlines()
                )
                broken = VALID_TRACKER[:block_start] + nested + VALID_TRACKER[block_end:]
                with self.assertRaises(TrackerValidationError):
                    validate_fixture(broken)

    def test_rejects_release_not_applicable_bypass(self) -> None:
        broken = VALID_TRACKER.replace(
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[?]` |",
            "| Pairing | HYP-M0-001 | `[x]` | `[x]` | `[?]` | `[-]` |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "N/A is not allowed"):
            validate_fixture(broken)

    def test_rejects_placeholder_blocker(self) -> None:
        broken = VALID_TRACKER.replace("Device run is open.", ".")

        with self.assertRaisesRegex(TrackerValidationError, "actionable blocker"):
            validate_fixture(broken)

    def test_rejects_non_visible_blocker_source_text(self) -> None:
        invisible_blockers = (
            "&nbsp;&nbsp;&nbsp;",
            "&#8203;&#8203;&#8203;&#8203;",
            "&#4447;" * 12,
            "&#4448;" * 12,
            "&#12644;" * 12,
            "&#65440;" * 12,
            "\U00013441" * 12,
            "&#78913;" * 12,
            "&#x13442;" * 12,
            "[](https://example.com/this-url-is-not-visible-text)",
            "![](https://example.com/this-image-has-no-visible-alt-text.png)",
            "[![](x)](https://abcdefghijklmnop.example)",
            "[![](x)](https://abcdefghijklmnop.example/path_(nested))",
            "[][abcdefghijklmnop]",
            "![alt][abcdefghijklmnop]",
            "![abcdefghijklmnop]",
            "![alt][]",
            "![![x](inner)abcdefghijklmnop](outer)",
            "![[x](inner)abcdefghijklmnop](outer)",
        )
        for blocker in invisible_blockers:
            with self.subTest(blocker=blocker):
                broken = VALID_TRACKER.replace("Device run is open.", blocker).replace(
                    "<!-- roadmap-evidence:end -->",
                    "<!-- roadmap-evidence:end -->\n\n"
                    "[abcdefghijklmnop]: https://example.com/proof\n"
                    "[alt]: https://example.com/proof",
                )
                with self.assertRaisesRegex(TrackerValidationError, "actionable blocker"):
                    validate_fixture(broken)

    def test_release_candidate_and_tag_require_every_release_journey_to_pass(self) -> None:
        with_release_tasks = VALID_TRACKER.replace(
            "| HYP-M0-004 | `[ ]` | P1 |",
            "| HYP-M0-004 | `[ ]` | P1 |\n"
            "| HYP-M6-009 | `[x]` | P0 |\n"
            "| HYP-M6-010 | `[x]` | P0 |",
        ).replace(
            "| M0 Test | 1 | 1 | 1 | 1 |",
            "| M0 Test | 1 | 1 | 1 | 1 |\n| M6 Test | 2 | 0 | 0 | 0 |",
        )

        with self.assertRaisesRegex(TrackerValidationError, "HYP-M6-009/010 cannot be done"):
            validate_tracker(
                with_release_tasks,
                required_task_ids=FIXTURE_TASK_IDS | {"HYP-M6-009", "HYP-M6-010"},
                required_journey_task_ids=FIXTURE_JOURNEY_TASK_IDS,
                allowed_na_layers={},
                task_table_headers=FIXTURE_TASK_TABLE_HEADERS,
            )

    def test_release_candidate_requires_all_explicit_m6_prerequisites(self) -> None:
        statuses = {
            **{f"HYP-M6-{number:03d}": "[?]" for number in range(1, 9)},
            "HYP-M6-009": "[x]",
            "HYP-M6-010": "[?]",
        }

        with self.assertRaisesRegex(TrackerValidationError, "HYP-M6-009 cannot be done while prerequisites"):
            validate_tracker(
                tracker_with_m6(statuses),
                required_task_ids=FIXTURE_TASK_IDS | set(statuses),
                required_journey_task_ids=FIXTURE_JOURNEY_TASK_IDS,
                allowed_na_layers={},
                task_table_headers=FIXTURE_TASK_TABLE_HEADERS,
            )

    def test_v1_tag_requires_release_candidate(self) -> None:
        statuses = {
            **{f"HYP-M6-{number:03d}": "[x]" for number in range(1, 9)},
            "HYP-M6-009": "[?]",
            "HYP-M6-010": "[x]",
        }

        with self.assertRaisesRegex(TrackerValidationError, "HYP-M6-010 cannot be done before HYP-M6-009"):
            validate_tracker(
                tracker_with_m6(statuses),
                required_task_ids=FIXTURE_TASK_IDS | set(statuses),
                required_journey_task_ids=FIXTURE_JOURNEY_TASK_IDS,
                allowed_na_layers={},
                task_table_headers=FIXTURE_TASK_TABLE_HEADERS,
            )


if __name__ == "__main__":
    unittest.main()
