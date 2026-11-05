"""Check that filtering lint categories never turns a broken scan into success."""
import contextlib
import io
import json
import runpy
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

MAIN = runpy.run_path(str(Path(__file__).with_name("dead-code")))["main"]


class DeadCodeTest(unittest.TestCase):
    def run_report(self, findings, files=1, returncode=0):
        report = {"findings": findings, "summary": {"files": files}}
        completed = subprocess.CompletedProcess([], returncode, json.dumps(report), "")
        with patch("subprocess.run", return_value=completed), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            return MAIN()

    def finding(self, kind):
        return {"type": kind, "filename": "src/demo.clj", "row": 1, "col": 1,
                "message": "fixture finding"}

    def test_unused_code_fails(self):
        self.assertEqual(self.run_report([self.finding("unused-private-var")], returncode=2), 2)

    def test_syntax_errors_fail(self):
        self.assertEqual(self.run_report([self.finding("syntax")], returncode=3), 2)

    def test_unrelated_lint_is_not_misreported_as_dead_code(self):
        self.assertEqual(self.run_report([self.finding("unresolved-symbol")], returncode=3), 0)

    def test_empty_and_failed_scans_fail(self):
        self.assertEqual(self.run_report([], files=0), 1)
        self.assertEqual(self.run_report([], returncode=1), 1)

    def test_invalid_report_fails(self):
        completed = subprocess.CompletedProcess([], 1, "not JSON", "tool failed")
        with patch("subprocess.run", return_value=completed), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(MAIN(), 1)


if __name__ == "__main__":
    unittest.main()
