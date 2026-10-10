#!/usr/bin/env python3
"""Create a disposable Gradle fixture using a generated local Maven repository."""
import argparse
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="New, empty fixture directory")
    parser.add_argument("--repository", type=Path, required=True,
                        help="Repository produced by create-native-maven-fixture.py with the same declaration count")
    parser.add_argument("--modules", type=int, default=1, help="Dependency-bearing projects; large fixtures also have a root aggregator")
    parser.add_argument("--declarations-per-module", type=int, default=3)
    parser.add_argument("--dsl", choices=("groovy", "kotlin"), default="groovy")
    parser.add_argument("--catalog", action="store_true", help="Use one shared catalog version instead of script literals")
    args = parser.parse_args()
    if args.modules < 1 or args.declarations_per_module < 1:
        parser.error("module and declaration counts must be positive")
    root = args.directory.resolve()
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        parser.error("fixture directory must be empty")
    repository = args.repository.resolve()
    names = ["alpha", "beta", "gamma"] if args.declarations_per_module == 3 else [
        f"package-{index:04d}" for index in range(args.declarations_per_module)]
    if any(not (repository / "fixture" / name / "maven-metadata.xml").is_file() for name in names):
        parser.error("repository must contain fixture artifacts for the requested declaration count")
    project_root = Path(__file__).resolve().parent.parent
    wrapper_files = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]
    if any(not (project_root / name).is_file() for name in wrapper_files):
        parser.error("run this script from a checkout containing the Gradle wrapper")
    root.mkdir(parents=True, exist_ok=True)
    suffix = ".gradle.kts" if args.dsl == "kotlin" else ".gradle"
    project_name = "native-gradle-small" if args.modules == 1 else "native-gradle-large"
    settings = f"rootProject.name = {json.dumps(project_name)}\n"
    if args.modules > 1:
        settings += "include(" + ", ".join(json.dumps(f"module-{index:03d}") for index in range(args.modules)) + ")\n"
    settings += "dependencyResolutionManagement { repositories { maven { url = uri(" + json.dumps(repository.as_uri()) + ") } } }\n"
    (root / ("settings" + suffix)).write_text(settings, encoding="utf-8")
    if args.catalog:
        catalog = root / "gradle/libs.versions.toml"
        catalog.parent.mkdir(parents=True, exist_ok=True)
        catalog.write_text('[versions]\nshared = "1.0.0"\n[libraries]\n' + "".join(
            f'library{index:04d} = {{ module = "fixture:{name}", version.ref = "shared" }}\n'
            for index, name in enumerate(names)), encoding="utf-8")
    if args.modules > 1:
        (root / ("build" + suffix)).write_text("// Dependencies live in the subprojects.\n", encoding="utf-8")
    for index in range(args.modules):
        module = root if args.modules == 1 else root / f"module-{index:03d}"
        module.mkdir(parents=True, exist_ok=True)
        build = "plugins { java }\n" if args.dsl == "kotlin" else "plugins { id 'java' }\n"
        build += "dependencies {\n"
        for declaration, name in enumerate(names):
            if args.catalog:
                build += f"    implementation(libs.library{declaration:04d})\n"
            elif args.dsl == "kotlin":
                build += f'    implementation("fixture:{name}:1.0.0")\n'
            else:
                build += f"    implementation 'fixture:{name}:1.0.0'\n"
        (module / ("build" + suffix)).write_text(build + "}\n", encoding="utf-8")
        source = module / "src/main/java/Fixture.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("public class Fixture {}\n", encoding="utf-8")
    for name in wrapper_files:
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(project_root / name, destination)
    print(root)
    print(f"{args.modules} dependency-bearing projects, {args.modules * len(names)} dependency uses")
    if args.catalog:
        print(f"{len(names)} catalog library declarations sharing one version")


if __name__ == "__main__":
    main()
