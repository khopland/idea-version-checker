# npm demo

A small npm workspace with old exact, `^` and `~` versions. It needs no `npm install` and no Node.js interpreter.

1. Copy it from the repository root: `mkdir -p examples && cp -R src/test/resources/npm-demo examples/npm-demo`.
2. Open `examples/npm-demo` in the sandbox IDE from `./gradlew runIde`.
3. Open a `package.json`. Warnings show on outdated versions, and **Alt+Enter** keeps the `^` or `~`.

Aliases, tags, `workspace:` and complex ranges are skipped. The local `@demo/library` package is not in the registry, so it shows nothing.
Try **Tools → Versions → Update All Files → Patch** to update every manifest in one undoable edit.
