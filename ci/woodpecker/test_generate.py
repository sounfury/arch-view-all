"""Check generation, policy validation and safe migration of existing tests."""
import contextlib
import io
import json
import re
import tempfile
import tomllib
import unittest
from pathlib import Path

import generate


class GenerationTest(unittest.TestCase):
    def policy(self, profile="clojure"):
        return {"version": 1, "profile": profile, "sources": ["src"],
                "duplication_sources": ["src"],
                "limits": generate.DEFAULT_LIMITS.copy()}

    def test_both_profiles_have_eight_independent_gates(self):
        for profile in ("clojure", "kotlin"):
            with self.subTest(profile=profile):
                files = generate.generate(self.policy(profile))
                gates = [name for name in files if name.startswith(".woodpecker/")]
                self.assertEqual(len(gates), 8)
                security = files[".woodpecker/密钥扫描.yaml"]
                self.assertNotIn("SKIP_", security)
                self.assertNotIn("failure:", security)
                self.assertIn("partial: false", security)
                self.assertIn("--redact=100", security)
                for title, _, variable in generate.GATES:
                    text = files[f".woodpecker/{title}.yaml"]
                    self.assertIn(f"SKIP_{variable}", text)
                    self.assertIn(f"{variable}_NONBLOCK", text)
                    self.assertIn('failure: "ignore"', text)

    def test_cyclomatic_limit_is_inclusive(self):
        files = generate.generate(self.policy())
        self.assertIn("--fail-on-complexity 13", files[".woodpecker/圈复杂度.yaml"])
        self.assertIn("**/assets/**", json.loads(files["quality/jscpd.json"])["ignore"])

    def test_kotlin_thresholds_match_zhiying(self):
        files = generate.generate(self.policy("kotlin"))
        self.assertIn("allowedComplexity: 12", files["quality/detekt-cyclomatic.yml"])
        self.assertIn("ignoreSimpleWhenEntries: true", files["quality/detekt-cyclomatic.yml"])
        self.assertIn("allowedConstructorParameters: 6", files["quality/detekt-params.yml"])
        self.assertIn("--analysis-mode full --classpath", files[".woodpecker/参数个数.yaml"])
        policy = self.policy("kotlin")
        policy["sources"] = ["backend", "shared"]
        self.assertIn("--input backend:shared", generate.generate(policy)[".woodpecker/圈复杂度.yaml"])

    def test_secret_exceptions_are_literal_and_scoped(self):
        policy = self.policy()
        self.assertNotIn("allowlists", tomllib.loads(generate.gitleaks_config(policy)))
        policy["secret_allowlists"] = [{
            "description": "Reviewed upstream expression",
            "rule": "generic-api-key",
            "path": "src/vendor/mermaid.min.js",
            "value": "v=m.atlasCount",
        }]
        generate.validate(policy)
        config = tomllib.loads(generate.gitleaks_config(policy))
        self.assertTrue(config["extend"]["useDefault"])
        allow = config["allowlists"][0]
        self.assertEqual(allow["condition"], "AND")
        self.assertEqual(allow["targetRules"], ["generic-api-key"])
        self.assertEqual(allow["regexTarget"], "secret")
        self.assertIsNotNone(re.fullmatch(allow["paths"][0], "src/vendor/mermaid.min.js"))
        self.assertIsNone(re.fullmatch(allow["paths"][0], "src/vendor/other.js"))
        self.assertIsNotNone(re.fullmatch(allow["regexes"][0], "v=m.atlasCount"))
        self.assertIsNone(re.fullmatch(allow["regexes"][0], "v=mXatlasCount"))
        self.assertIsNone(re.fullmatch(allow["regexes"][0], "v=m.atlasCountChanged"))

    def test_incomplete_secret_exceptions_are_rejected(self):
        for exception in ({"path": "src/vendor.js"}, {
                "description": "Reviewed", "rule": "generic-api-key",
                "path": "../outside.js", "value": "v=m.atlasCount"}, {
                "description": "Reviewed", "rule": "generic-api-key",
                "path": "src/vendor.js", "value": "key"}):
            policy = self.policy()
            policy["secret_allowlists"] = [exception]
            with self.assertRaises(ValueError):
                generate.validate(policy)

    def test_invalid_policy_fails_before_writing(self):
        for source in ("../outside", "/absolute", "--option"):
            policy = self.policy()
            policy["sources"] = [source]
            with self.assertRaises(ValueError):
                generate.validate(policy)
        policy = self.policy()
        policy["limits"]["cyclomatic"] = True
        with self.assertRaises(ValueError):
            generate.validate(policy)

    def test_existing_tests_are_preserved_and_drift_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            original = "steps:\n  - name: existing-test\n    image: node:22\n"
            (root / ".woodpecker.yaml").write_text(original)
            self.assertEqual(generate.main(["--project", tmp, "--migrate-tests"]), 0)
            saved = root / ".woodpecker/回归测试.yaml"
            self.assertEqual(saved.read_text(), original)
            self.assertFalse((root / ".woodpecker.yaml").exists())
            self.assertEqual(generate.main(["--project", tmp, "--check"]), 0)
            policy = root / "quality/woodpecker.json"
            data = json.loads(policy.read_text())
            data["limits"]["params"] = 4
            policy.write_text(json.dumps(data))
            self.assertEqual(generate.main(["--project", tmp, "--check"]), 1)
            self.assertEqual(saved.read_text(), original)

    def test_yml_root_workflow_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            original = root / ".woodpecker.yml"
            original.write_text("original workflow")
            self.assertEqual(generate.main(["--project", tmp, "--migrate-tests"]), 0)
            self.assertEqual((root / ".woodpecker/回归测试.yaml").read_text(),
                             "original workflow")
            self.assertFalse(original.exists())

    def test_collision_does_not_move_existing_tests(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
            root = Path(tmp)
            original = root / ".woodpecker.yaml"
            original.write_text("existing workflow")
            conflict = root / ".woodpecker/圈复杂度.yaml"
            conflict.parent.mkdir()
            conflict.write_text("hand written workflow")
            with self.assertRaises(SystemExit):
                generate.main(["--project", tmp, "--migrate-tests"])
            self.assertTrue(original.exists())
            self.assertEqual(conflict.read_text(), "hand written workflow")


if __name__ == "__main__":
    unittest.main()
