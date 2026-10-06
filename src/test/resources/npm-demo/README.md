# npm workspace demo

This template has deliberately old exact/caret/tilde selectors, a scoped package, an npm alias, a local workspace dependency, a peer declaration and a complex range. It needs no dependency installation for version checks.

Copy it from the repository root before trying updates:

```bash
mkdir -p examples
cp -R src/test/resources/npm-demo examples/npm-demo
```

Open `examples/npm-demo` as an IntelliJ project with JavaScript and TypeScript enabled. Configure a local Node.js interpreter and npm in the JavaScript runtime settings. If the example already exists, use your existing copy.

1. Open `packages/app/package.json` and wait for inspection results. Alt+Enter on `lodash` offers **Update locally** for this declaration or **Update across workspace** for the root, app and library declarations, including the root's alias. The workspace fix preserves the root/library caret, app's exact version and alias's tilde. Apply either choice, inspect the manifests and Undo before continuing. The root manifest offers the same two choices on `lodash`; packages used in only one manifest keep a single quick fix.
2. Use **Tools → Update Versions → Current File → Patch Only**. The preview should include root declarations only, retaining the caret/tilde and alias operators.
3. Use **Whole Project → Minor + Patch** to include the workspace manifests. `@demo/library` is local; `peerDependencies/react-dom` requires compatibility review; the `@types/node` complex range requires selector review.
4. Try **Major + Minor + Patch** to preview newer major branches. Review application compatibility before applying.
5. Apply a preview and verify that only dependency strings change. The package's own version stays `1.0.0`. Use Undo to revert the command.

The checker does not install packages or generate/update lockfiles. Use IntelliJ's package-manager actions or npm yourself afterward if you want to synchronize them. Registry results depend on the current published metadata, so exact suggested versions vary over time.

To test deprecation highlighting without relying on registry changes, add `lodash = Demo policy notice` under **Settings → Tools → Version Checker → Explicitly deprecated dependencies or plugins**, then refresh checks. It should appear as an error by default and require replacement review.
