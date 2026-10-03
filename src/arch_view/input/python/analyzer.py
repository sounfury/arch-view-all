# 职责：读取脚本源码（Python），找出模块、导入关系和抽象类，不执行目标项目的代码。
# 核心入口：主分析流程（main），分析结果通过标准输出交给调用方。

"""Static Python dependency discovery. Never imports or executes project code."""
import ast
import json
import os
from pathlib import Path
import sys
import tokenize


EXCLUDED = {"__pycache__", "node_modules", "venv", ".venv", "env",
            "build", "dist", "site-packages"}


TEST_DIRECTORIES = {"test", "tests"}


def is_test_file(name):
    return name.startswith("test_") or name.endswith("_test.py") or name in {"test.py", "conftest.py"}


def discover(project, source_paths, include_tests=False):
    modules = {}
    for source in source_paths:
        root = (Path(project) / source).resolve()
        if not root.is_dir():
            raise ValueError(f"Source directory does not exist: {root}")
        if not include_tests and any(part in TEST_DIRECTORIES for part in Path(source).parts):
            continue
        # A selected package directory retains its own import name.
        base = root.parent if (root / "__init__.py").is_file() else root
        for directory, dirs, files in os.walk(root, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in EXCLUDED
                             and (include_tests or d not in TEST_DIRECTORIES)
                             and not d.startswith(".")
                             and not Path(directory, d).is_symlink())
            for filename in sorted(files):
                if not filename.endswith(".py") or (not include_tests and is_test_file(filename)):
                    continue
                path = Path(directory, filename)
                parts = list(path.relative_to(base).with_suffix("").parts)
                if parts[-1] == "__init__":
                    parts.pop()
                if not parts or not all(p.isidentifier() for p in parts):
                    continue
                module = ".".join(parts)
                if module in modules and modules[module] != path:
                    raise ValueError(f"Duplicate module {module}: {modules[module]} and {path}")
                modules[module] = path
    return modules


def import_base(node, module, is_package):
    if not node.level:
        return node.module or ""
    package = module.split(".") if is_package else module.split(".")[:-1]
    if node.level > len(package):
        return None
    return ".".join(package[:len(package) - node.level + 1]
                    + ([node.module] if node.module else []))


def dependencies(tree, module, is_package, modules):
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name in modules:
                    yield alias.name
        elif isinstance(node, ast.ImportFrom):
            base = import_base(node, module, is_package)
            if base is None:
                continue
            if base in modules:
                yield base
            for alias in node.names:
                target = f"{base}.{alias.name}" if base else alias.name
                if target in modules:
                    yield target


def qualified_name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return qualified_name(node.value) + "." + node.attr
    if isinstance(node, ast.Subscript):
        return qualified_name(node.value)
    if isinstance(node, ast.Call):
        return qualified_name(node.func)
    return ""


def is_abstract(tree):
    aliases = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                aliases[alias.asname or alias.name] = alias.name
        elif isinstance(node, ast.ImportFrom) and not node.level:
            for alias in node.names:
                aliases[alias.asname or alias.name] = f"{node.module}.{alias.name}"

    def resolve(node):
        head, *tail = qualified_name(node).split(".")
        return ".".join([aliases.get(head, head)] + tail)

    abstract_bases = {"abc.ABC", "abc.ABCMeta", "typing.Protocol", "typing_extensions.Protocol"}
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            bases = list(node.bases) + [k.value for k in node.keywords if k.arg == "metaclass"]
            if any(resolve(base) in abstract_bases for base in bases):
                return True
            for method in node.body:
                if isinstance(method, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    if any(resolve(d) == "abc.abstractmethod" for d in method.decorator_list):
                        return True
    return False


def analyze(project, source_paths, include_tests=False):
    modules = discover(project, source_paths, include_tests)
    edges, abstract = set(), []
    for module, path in sorted(modules.items()):
        with tokenize.open(path) as source:
            tree = ast.parse(source.read(), filename=str(path))
        edges.update((module, target) for target in
                     dependencies(tree, module, path.name == "__init__.py", modules)
                     if target != module)
        if is_abstract(tree):
            abstract.append(module)
    return {"nodes": sorted(modules), "edges": [list(e) for e in sorted(edges)],
            "abstract-modules": abstract,
            "module->source-file": {m: str(p) for m, p in sorted(modules.items())}}


def edn(value):
    if isinstance(value, dict):
        return "{" + " ".join(edn(k) + " " + edn(v) for k, v in value.items()) + "}"
    if isinstance(value, list):
        return "[" + " ".join(edn(v) for v in value) + "]"
    return json.dumps(value, ensure_ascii=False)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
        arguments = sys.argv[2:]
        include_tests = "--include-tests" in arguments
        print(edn(analyze(sys.argv[1], [a for a in arguments if a != "--include-tests"], include_tests)))
    except (OSError, SyntaxError, ValueError) as error:
        print(f"Python analysis failed: {error}", file=sys.stderr)
        sys.exit(1)
