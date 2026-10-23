# pnpm, Yarn and Bun demos

These are separate workspace projects with old exact, caret and tilde selectors, aliases, local packages and declarations that require review. No dependency installation is needed for registry checks.

From the repository root, copy the demos into the ignored examples folder:

```bash
mkdir -p examples
cp -R src/test/resources/node-managers-demo examples/node-managers-demo
```

Open one of `pnpm`, `yarn` or `bun` as an IntelliJ project. Configure a local Node.js interpreter and the matching installed package-manager CLI in the JavaScript runtime settings. The templates name specific manager versions; adjust `packageManager` to your installed version if needed. The checker uses the configured runtime and does not download a missing manager.

1. Open `packages/app/package.json`. Alt+Enter on `lodash` offers **Update locally** or **Update across workspace**. The workspace choice includes the root, library and root alias, preserving their different range operators. Apply and Undo to inspect the difference.
2. Use **Tools → Update Versions → Current File → Patch Only**. Only the selected manifest should be included.
3. Try **Whole Project → Minor + Patch**, then **Major + Minor + Patch**, and inspect the combined manifest preview.
4. Local `@demo/library`, peer compatibility and complex `@types/node` ranges require review. In pnpm and Bun, `chalk` uses `catalog:` and is reported for a manual catalog update. Catalog definitions remain untouched.
5. Apply a preview and verify that dependency strings change, with no package installation or lockfile edits. One Undo restores all workspace changes.

pnpm workspace membership comes from `pnpm-workspace.yaml`. Yarn and Bun use `package.json`; Yarn reads `.yarnrc.yml`, and Bun reads `bunfig.toml`. Yarn Classic is supported too: configure its installed CLI and change the template's manager version to `yarn@1.22.22`; Classic uses `.npmrc`/`.yarnrc` rather than modern Yarn's `.yarnrc.yml` registry settings.

To synchronize installed packages and lockfiles after reviewing edits, use IntelliJ or the package manager yourself.
