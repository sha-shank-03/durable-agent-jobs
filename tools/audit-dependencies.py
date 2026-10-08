"""Offline attribution from repository caches; no resolution/download or legal certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

REPO = Path(__file__).resolve().parents[1]
CACHE = REPO / "work/m2"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TEXTS = REPO / "docs/third-party-licenses"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def text(root, tag):
    return root.findtext("m:" + tag, default="", namespaces=NS)


def licenses(pom, seen=None):
    seen = set() if seen is None else seen
    if not pom.is_file() or pom in seen:
        return [], ["Missing/cyclic cached POM: " + str(pom.relative_to(REPO))]
    seen.add(pom)
    root = ET.parse(pom).getroot()
    declared = root.findall("m:licenses/m:license", NS)
    if declared:
        return [dict(name=text(item, "name"), url=text(item, "url"),
                     evidence=str(pom.relative_to(REPO)).replace("\\", "/"),
                     pom_sha256=digest(pom.read_bytes())) for item in declared], []
    parent = root.find("m:parent", NS)
    if parent is None:
        return [], ["No license declaration in cached POM/parents"]
    group, artifact, version = (text(parent, field) for field in ("groupId", "artifactId", "version"))
    properties = {node.tag.split("}")[-1]: node.text or "" for node in root.findall("m:properties/*", NS)}
    for key, value in properties.items():
        version = version.replace("$" + "{" + key + "}", value)
    return licenses(CACHE / group.replace(".", "/") / artifact / version / (artifact + "-" + version + ".pom"), seen)


def retain(data, label):
    suffix = digest(data)
    destination = TEXTS / (suffix + ".txt")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        destination.write_bytes(data)
    return dict(source=label, path=str(destination.relative_to(REPO)).replace("\\", "/"),
                bytes=len(data), sha256=suffix)


def jar_documents(jar):
    result = []
    with zipfile.ZipFile(jar) as archive:
        for entry in sorted(archive.namelist()):
            if re.fullmatch(r"(LICENSE|NOTICE|COPYING)(\.[^/]*)?", entry.split("/")[-1], re.I):
                result.append(retain(archive.read(entry), str(jar.relative_to(REPO)).replace("\\", "/") + "!/" + entry))
    return result


def actual_realms(log):
    result = {}
    realm = None
    if not log or not log.is_file():
        return result
    for line in log.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        match = re.search(r"Populating class realm (plugin>\S+)", line)
        if match:
            realm = match.group(1)
        match = re.search(r"Included: ([\w.:-]+)", line)
        if match and realm:
            fields = match.group(1).split(":")
            gav = ":".join((fields[0], fields[1], fields[-1]))
            result.setdefault(gav, set()).add(realm)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--realm-log", type=Path)
    args = parser.parse_args()
    realms = actual_realms(args.realm_log)
    classpath = set()
    for report in (REPO / "target/surefire-reports").glob("TEST-*.xml"):
        for prop in ET.parse(report).getroot().findall("properties/property"):
            if prop.get("name") == "java.class.path":
                classpath.update(Path(entry).resolve() for entry in prop.get("value", "").split(os.pathsep) if entry.endswith(".jar"))
    project = json.loads((REPO / "docs/resolved-dependencies.json").read_text(encoding="utf-8-sig"))
    project_scopes = {":".join((item["group_id"], item["artifact_id"], item["version"])): item["scope"]
                      for item in project["dependencies"]}
    cached, unresolved = [], []
    for jar in sorted(CACHE.rglob("*.jar")):
        parts = jar.relative_to(CACHE).parts
        group, artifact, version = ".".join(parts[:-3]), parts[-3], parts[-2]
        gav = ":".join((group, artifact, version))
        pom = jar.with_suffix(".pom")
        declared, gaps = licenses(pom)
        record = dict(coordinate=gav, jar_sha256=digest(jar.read_bytes()), jar_bytes=jar.stat().st_size,
                      pom_url="https://repo.maven.apache.org/maven2/" + str(pom.relative_to(CACHE)).replace("\\", "/"),
                      licenses=declared, retained_texts=jar_documents(jar), contexts=sorted(realms.get(gav, [])))
        if gav in project_scopes:
            record["contexts"].append("project_" + project_scopes[gav])
        if jar.resolve() in classpath:
            record["contexts"].append("actual_final_surefire_classpath")
        if not record["contexts"]:
            record["contexts"] = ["cached_build_or_provider_candidate"]
        if gaps:
            record["metadata_gaps"] = gaps
            unresolved.append(dict(coordinate=gav, gaps=gaps))
        cached.append(record)
    distribution = []
    maven_roots = list((REPO / "work/maven-user-home").rglob("maven-core-3.10.0.jar"))
    if len(maven_roots) != 1:
        raise ValueError("Expected exactly one repo-local Maven 3.10.0 distribution")
    maven = maven_roots[0].parent.parent
    distribution_texts = []
    for file in sorted(maven.rglob("*")):
        if file.is_file() and (file.suffix == ".license" or file.name in {"LICENSE", "NOTICE"}):
            distribution_texts.append(retain(file.read_bytes(), str(file.relative_to(REPO)).replace("\\", "/")))
    for jar in sorted(maven.rglob("*.jar")):
        library_name = re.split(r"-(?=\d)", jar.stem, maxsplit=1)[0]
        associated = [item for item in distribution_texts if item["source"].endswith("/" + library_name + ".license")]
        if not associated:
            associated = [item for item in distribution_texts if item["source"].endswith("/LICENSE") or item["source"].endswith("/NOTICE")]
        distribution.append(dict(file=str(jar.relative_to(maven)).replace("\\", "/"),
                                 jar_bytes=jar.stat().st_size, jar_sha256=digest(jar.read_bytes()),
                                 retained_texts=jar_documents(jar), distribution_license_references=associated))
    plugin_descriptors = []
    for plugin in ET.parse(REPO / "pom.xml").getroot().findall("m:build/m:plugins/m:plugin", NS):
        group = text(plugin, "groupId") or "org.apache.maven.plugins"
        artifact, version = text(plugin, "artifactId"), text(plugin, "version")
        jar = CACHE / group.replace(".", "/") / artifact / version / (artifact + "-" + version + ".jar")
        record = dict(coordinate=":".join((group, artifact, version)), cached=jar.exists(),
                      method="Declared dependency list from actual cached plugin JAR descriptor; not a claim that core imports/all entries are loaded")
        if jar.exists():
            with zipfile.ZipFile(jar) as archive:
                descriptor = archive.read("META-INF/maven/plugin.xml")
            record["descriptor_sha256"] = digest(descriptor)
            record["declared_dependencies"] = [{field: dep.findtext(field, "") for field in ("groupId", "artifactId", "version", "type")}
                                               for dep in ET.fromstring(descriptor).findall("dependencies/dependency")]
        plugin_descriptors.append(record)
    inject = CACHE / "javax/inject/javax.inject/1/javax.inject-1.jar"
    assert inject.read_bytes() == (maven / "lib/javax.inject-1.jar").read_bytes()
    supplements = [
        dict(coordinate="javax.inject:javax.inject:1", license_observation="Apache-2.0",
             evidence="Installed Maven lib/javax.inject.license; distribution JAR verified byte-identical to cached artifact",
             retained_text=retain((maven / "lib/javax.inject.license").read_bytes(), "Maven 3.10.0/lib/javax.inject.license")),
        dict(coordinate="org.jdom:jdom2:2.0.6.1", license_observation="JDOM BSD-style license with additional naming condition",
             evidence="Exact cached JAR META-INF/LICENSE.txt; full text retained without relabeling it as standard BSD-3-Clause")]
    inventory = dict(observed_date="2026-10-08", method="Offline cached POM inheritance, JAR license/notice bytes and installed Maven distribution",
                     realm_log=str(args.realm_log) if args.realm_log else None,
                     cached_artifacts=cached, maven_distribution=dict(version="3.10.0", jars=distribution, license_texts=distribution_texts),
                     final_surefire_classpath_jars=len(classpath), plugin_descriptors=plugin_descriptors,
                     unresolved_pom_metadata=unresolved, additional_license_evidence=supplements,
                     limits=["Cached candidates are a superset, not a claim that each is loaded.",
                             "Plugin contexts come only from actual realm debug lines when supplied.",
                             "Surefire provider candidates/core imports are covered by the cache/distribution inventory without claiming exact load order.",
                             "POM license declarations and retained upstream bytes are attribution evidence, not a legal opinion.",
                             "JDK/Python/hosted runners/action-bundled implementation dependencies are external prerequisites, not redistributed project artifacts."])
    destination = REPO / "docs/dependency-inventory.json"
    destination.write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(dict(cached_artifacts=len(cached), maven_distribution_jars=len(distribution),
                         final_surefire_classpath_jars=len(classpath), cached_plugin_descriptors=sum(item["cached"] for item in plugin_descriptors),
                         artifacts_with_actual_plugin_context=sum(any(x.startswith("plugin>") for x in item["contexts"]) for item in cached),
                         retained_unique_texts=len(list(TEXTS.glob("*.txt"))), unresolved_pom_metadata=len(unresolved))))


if __name__ == "__main__":
    main()
