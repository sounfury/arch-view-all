"""Behavior tests for static Python analysis; run with unittest discovery."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "src/arch_view/input/python/analyzer.py"
SPEC = importlib.util.spec_from_file_location("analyzer", SCRIPT)
analyzer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analyzer)


class PythonAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, content=""):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_given_import_variants_when_analyzed_then_only_internal_dependencies(self):
        self.write("src/pkg/__init__.py", "from . import api")
        self.write("src/pkg/api.py", "from abc import ABC as Base\nclass Port(Base): pass")
        self.write("src/pkg/sub/__init__.py")
        self.write("src/pkg/sub/service.py", """
import os, pkg.api as api
from ..api import (
    Port as ServicePort,
)
from .. import api
from . import helper
from ..missing import ignored
text = 'import pkg.fake'
# import pkg.fake
def deferred():
    import pkg.api
raise RuntimeError('must never execute')
""")
        self.write("src/pkg/sub/helper.py")
        self.write("src/pkg/fake.py")
        result = analyzer.analyze(self.root, ["src"])
        self.assertEqual({("pkg", "pkg.api"), ("pkg.sub.service", "pkg.api"),
                          ("pkg.sub.service", "pkg"), ("pkg.sub.service", "pkg.sub"),
                          ("pkg.sub.service", "pkg.sub.helper")},
                         {tuple(edge) for edge in result["edges"]})
        self.assertEqual(["pkg.api"], result["abstract-modules"])
        self.assertEqual(str(self.root / "src/pkg/api.py"), result["module->source-file"]["pkg.api"])

    def test_given_flat_namespace_packages_and_venv_then_scans_project_only(self):
        self.write("app.py", "from ns import helper")
        self.write("ns/helper.py")
        self.write(".venv/lib/noise.py", "not valid python !")
        self.write("venv/noise.py")
        self.write("build/noise.py")
        result = analyzer.analyze(self.root, ["."])
        self.assertEqual(["app", "ns.helper"], result["nodes"])
        self.assertEqual([["app", "ns.helper"]], result["edges"])

    def test_given_package_root_then_preserves_package_name_and_resolves_star(self):
        self.write("pkg/__init__.py")
        self.write("pkg/a.py", "from .b import *")
        self.write("pkg/b.py", "from ...pkg import a")
        result = analyzer.analyze(self.root, ["pkg"])
        self.assertEqual(["pkg", "pkg.a", "pkg.b"], result["nodes"])
        self.assertEqual([["pkg.a", "pkg.b"]], result["edges"])

    def test_given_abstract_classes_then_marks_aliases_protocols_and_decorators(self):
        self.write("protocol.py", "import typing as t\nclass P(t.Protocol): pass")
        self.write("meta.py", "from abc import ABCMeta as M\nclass P(metaclass=M): pass")
        self.write("method.py", "import abc\nclass P:\n @abc.abstractmethod\n def f(self): pass")
        self.write("concrete.py", "class ABC: pass\nclass P(ABC): pass")
        self.assertEqual(["meta", "method", "protocol"],
                         analyzer.analyze(self.root, ["."])["abstract-modules"])

    def test_given_multiple_roots_then_links_modules_across_roots(self):
        self.write("src/a.py", "import b")
        self.write("lib/b.py", "import a")
        self.assertEqual([["a", "b"], ["b", "a"]],
                         analyzer.analyze(self.root, ["src", "lib"])["edges"])

    def test_given_invalid_syntax_then_reports_source_filename(self):
        path = self.write("bad.py", "def broken(")
        with self.assertRaises(SyntaxError) as error:
            analyzer.analyze(self.root, ["."])
        self.assertEqual(str(path), error.exception.filename)

    def test_given_duplicate_modules_then_reports_ambiguity(self):
        self.write("src/a.py")
        self.write("lib/a.py")
        with self.assertRaisesRegex(ValueError, "Duplicate module a"):
            analyzer.analyze(self.root, ["src", "lib"])

    def test_given_missing_root_then_reports_error(self):
        with self.assertRaisesRegex(ValueError, "Source directory does not exist"):
            analyzer.analyze(self.root, ["missing"])


if __name__ == "__main__":
    unittest.main()
