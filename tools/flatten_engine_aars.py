"""Flatten the two patched local AARs into inputs consumable by a standalone AAR.

AGP does not allow a library module to publish another local AAR as a dependency.
This extracts bytecode, native libraries, assets, resources and manifest declarations
so the resulting engine AAR contains the actual runtime payload.
"""
from __future__ import annotations

import os
import shutil
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PSIPHON = Path(os.environ["ATOM_PSIPHON_AAR"])
V2RAY = Path(os.environ["ATOM_V2RAY_AAR"])
BASE_MANIFEST = ROOT / "engine/src/main/AndroidManifest.xml"
OUT = Path(os.environ["ATOM_ENGINE_FLAT_OUT"])
ANDROID = "{http://schemas.android.com/apk/res/android}"


def merge_manifest(target: ET.Element, incoming: ET.Element) -> None:
    for child in list(incoming):
        if child.tag == "uses-sdk":
            continue
        if child.tag == "application":
            app = target.find("application")
            if app is None:
                app = ET.SubElement(target, "application")
            for item in list(child):
                key = (item.tag, item.get(ANDROID + "name"), item.get(ANDROID + "authorities"))
                if not any(
                    (old.tag, old.get(ANDROID + "name"), old.get(ANDROID + "authorities")) == key
                    for old in list(app)
                ):
                    app.append(item)
            for k, v in child.attrib.items():
                if k not in app.attrib:
                    app.set(k, v)
            continue
        key = (child.tag, child.get(ANDROID + "name"))
        if not any((old.tag, old.get(ANDROID + "name")) == key for old in list(target)):
            target.append(child)


def extract_aar(aar: Path, label: str, manifest_root: ET.Element) -> None:
    print(f"Flattening {aar.name}")
    with zipfile.ZipFile(aar) as z:
        names = z.namelist()
        if "classes.jar" not in names:
            raise RuntimeError(f"{aar.name} has no classes.jar")
        libdir = OUT / "libs"
        libdir.mkdir(parents=True, exist_ok=True)
        (libdir / f"{label}-classes.jar").write_bytes(z.read("classes.jar"))

        for name in names:
            normalized = name.replace("\\", "/")
            if normalized.startswith("libs/") and normalized.endswith(".jar"):
                dest = libdir / f"{label}-{Path(normalized).name}"
                dest.write_bytes(z.read(name))
            elif normalized.startswith("jni/") and not normalized.endswith("/"):
                rel = normalized[len("jni/"):]
                dest = OUT / "jniLibs" / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(name))
            elif normalized.startswith("assets/") and not normalized.endswith("/"):
                rel = normalized[len("assets/"):]
                dest = OUT / "assets" / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(name))
            elif normalized.startswith("res/") and not normalized.endswith("/"):
                rel = normalized[len("res/"):]
                dest = OUT / "res" / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(name))

        if "AndroidManifest.xml" in names:
            raw = z.read("AndroidManifest.xml")
            try:
                incoming = ET.fromstring(raw)
            except ET.ParseError as exc:
                raise RuntimeError(
                    f"{aar.name} has a non-text/binary AndroidManifest.xml; "
                    "cannot safely merge it into the standalone library"
                ) from exc
            merge_manifest(manifest_root, incoming)
            print(f"  manifest package={incoming.get('package', '(none)')}")
        print(f"  entries={len(names)}; native={sum(n.startswith('jni/') for n in names)}; "
              f"assets={sum(n.startswith('assets/') for n in names)}; "
              f"resources={sum(n.startswith('res/') for n in names)}")


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    tree = ET.parse(BASE_MANIFEST)
    root = tree.getroot()
    extract_aar(PSIPHON, "psiphon", root)
    extract_aar(V2RAY, "v2ray", root)
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    tree.write(OUT / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
    print(f"Flattened AAR inputs written to {OUT}")


if __name__ == "__main__":
    main()
