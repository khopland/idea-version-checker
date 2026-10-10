#!/usr/bin/env python3
"""Create a disposable Maven fixture and a file repository for native IDEA validation."""
import argparse
from pathlib import Path
from xml.dom.minidom import parseString
from xml.sax.saxutils import escape
import zipfile


def write_xml(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(parseString(content).toprettyxml(indent="  "), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="New, empty fixture directory")
    parser.add_argument("--modules", type=int, default=1, help="Dependency-bearing modules; large fixtures also have an aggregator")
    parser.add_argument("--declarations-per-module", type=int, default=3)
    args = parser.parse_args()
    if args.modules < 1 or args.declarations_per_module < 1:
        parser.error("module and declaration counts must be positive")
    root = args.directory.resolve()
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        parser.error("fixture directory must be empty")
    root.mkdir(parents=True, exist_ok=True)
    repository = root / "repository"
    names = ["alpha", "beta", "gamma"] if args.declarations_per_module == 3 else [
        f"package-{index:04d}" for index in range(args.declarations_per_module)]
    versions = ("1.0.0", "1.0.1", "1.1.0", "2.0.0")
    for name in names:
        artifact = repository / "fixture" / name
        write_xml(artifact / "maven-metadata.xml", f"""<metadata><groupId>fixture</groupId>
          <artifactId>{name}</artifactId><versioning><latest>2.0.0</latest><release>2.0.0</release>
          <versions>{''.join(f'<version>{v}</version>' for v in versions)}</versions>
          <lastUpdated>20261010000000</lastUpdated></versioning></metadata>""")
        for version in versions:
            location = artifact / version
            write_xml(location / f"{name}-{version}.pom", f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>{name}</artifactId>
              <version>{version}</version></project>""")
            with zipfile.ZipFile(location / f"{name}-{version}.jar", "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    project = root / ("maven-small" if args.modules == 1 else "maven-large")
    dependencies = "".join(f"""<dependency><groupId>fixture</groupId><artifactId>{name}</artifactId>
      <version>1.0.0</version></dependency>""" for name in names)
    common = f"""<properties><maven.compiler.release>21</maven.compiler.release></properties>
      <repositories><repository><id>native-local</id><url>{escape(repository.as_uri())}</url></repository></repositories>"""
    header = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    if args.modules > 1:
        write_xml(project / "pom.xml", header + "<groupId>fixture</groupId><artifactId>native-large</artifactId>"
                  "<version>1.0.0</version><packaging>pom</packaging><modules>" +
                  "".join(f"<module>module-{i:03d}</module>" for i in range(args.modules)) + "</modules></project>")
    for index in range(args.modules):
        module = project if args.modules == 1 else project / f"module-{index:03d}"
        identity = "native-small" if args.modules == 1 else f"native-module-{index:03d}"
        write_xml(module / "pom.xml", header + f"<groupId>fixture</groupId><artifactId>{identity}</artifactId>"
                  "<version>1.0.0</version>" + common + "<dependencies>" + dependencies + "</dependencies></project>")
        source = module / "src/main/java/Fixture.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("public class Fixture {}\n")
    print(project)
    print(f"{args.modules} dependency-bearing modules, {args.modules * len(names)} declarations")


if __name__ == "__main__":
    main()
