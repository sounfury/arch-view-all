# Java adapter

`dependency_extract.clj` implements the shared `build-module-graph` contract using
[JavacTask](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html)
and the JDK Tree API. Run the viewer on a full JDK supporting the project's syntax.
No target code or annotation processors are executed, and no bytecode is emitted.

Modules are package-qualified top-level types; nested types belong to their
outermost type. Only resolved references to scanned types become edges, excluding
self edges. Unused imports do not count as dependencies. Interfaces, annotation
types and abstract top-level classes are abstract modules. Each node maps to its
canonical source file path.

The adapter accepts repeated source roots, deduplicates overlapping files, and
rejects duplicate qualified types and syntax errors. It ignores hidden/build
folders and package/module descriptors. Default roots include every nested
`src/main/java` directory, including the project root module. If none exist,
discovery falls back to `src`, then `.`. Standard test roots are excluded when
main roots are discovered. Use explicit source roots for custom layouts or tests.
Language detection is automatic, so multi-module Maven/Gradle projects normally
only need `--project-path`.

Missing external libraries are tolerated during symbol resolution. References
requiring those libraries or generated sources can be incomplete. Build settings,
annotation processing, reflection and runtime dependency injection are not analyzed.
