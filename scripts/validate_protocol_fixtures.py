#!/usr/bin/env python3
"""Dependency-free validator for Hyphen protocol schemas (HYP-M2-001).

Implements exactly the JSON Schema subset the project's schemas use:
type (incl. unions), required, properties, additionalProperties=False,
pattern, minimum, const, enum. Any other *constraint* keyword raises, so a
schema using unsupported features fails loudly instead of silently passing.

Layout convention:
  protocol/schema/<name>.schema.json
  protocol/test-vectors/<name>/valid/*.json    -> must validate
  protocol/test-vectors/<name>/invalid/*.json  -> must be rejected
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANNOTATIONS = {"$schema", "$id", "title", "description", "examples", "default"}
SUPPORTED = {"type", "required", "properties", "additionalProperties", "pattern",
             "minimum", "maximum", "maxLength", "const", "enum", "propertyNames"}

TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


class Invalid(ValueError):
    pass


def validate(instance, schema, path="$"):
    unsupported = set(schema) - ANNOTATIONS - SUPPORTED
    if unsupported:
        raise RuntimeError(f"schema uses unsupported keywords {unsupported} at {path}")

    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(TYPE_CHECKS[t](instance) for t in types):
            raise Invalid(f"{path}: expected type {types}, got {type(instance).__name__}")

    if "const" in schema and instance != schema["const"]:
        raise Invalid(f"{path}: expected const {schema['const']!r}")

    if "enum" in schema and instance not in schema["enum"]:
        raise Invalid(f"{path}: {instance!r} not in enum")

    if "pattern" in schema and isinstance(instance, str):
        if re.fullmatch(schema["pattern"], instance) is None:
            raise Invalid(f"{path}: {instance!r} does not match {schema['pattern']!r}")

    if "maxLength" in schema and isinstance(instance, str):
        if len(instance) > schema["maxLength"]:
            raise Invalid(f"{path}: string length {len(instance)} > maxLength {schema['maxLength']}")

    if "minimum" in schema and isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if instance < schema["minimum"]:
            raise Invalid(f"{path}: {instance} < minimum {schema['minimum']}")

    if "maximum" in schema and isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if instance > schema["maximum"]:
            raise Invalid(f"{path}: {instance} > maximum {schema['maximum']}")

    if isinstance(instance, dict):
        for field in schema.get("required", []):
            if field not in instance:
                raise Invalid(f"{path}: missing required field {field!r}")
        if "propertyNames" in schema:
            for key in instance:
                validate(key, schema["propertyNames"], f"{path}[propertyName {key!r}]")
        props = schema.get("properties", {})
        if schema.get("additionalProperties", True) is False:
            extra = set(instance) - set(props)
            if extra:
                raise Invalid(f"{path}: unknown fields {sorted(extra)}")
        for key, sub in props.items():
            if key in instance:
                validate(instance[key], sub, f"{path}.{key}")


def check_schema_keywords(schema, path="$"):
    unsupported = set(schema) - ANNOTATIONS - SUPPORTED
    if unsupported:
        raise RuntimeError(f"schema uses unsupported keywords {unsupported} at {path}")

    for key, sub in schema.get("properties", {}).items():
        check_schema_keywords(sub, f"{path}.properties.{key}")
    if "propertyNames" in schema:
        check_schema_keywords(schema["propertyNames"], f"{path}.propertyNames")


def main():
    schemas = sorted((ROOT / "protocol" / "schema").glob("*.schema.json"))
    if not schemas:
        print("no schemas found", file=sys.stderr)
        return 1

    failures = 0
    for schema_path in schemas:
        name = schema_path.name.replace(".schema.json", "")
        schema = json.loads(schema_path.read_text())
        try:
            check_schema_keywords(schema)
        except RuntimeError as e:
            print(f"  FAIL {name}: {e}")
            failures += 1
            continue
        vectors = ROOT / "protocol" / "test-vectors" / name

        for fixture in sorted((vectors / "valid").glob("*.json")):
            try:
                validate(json.loads(fixture.read_text()), schema)
                print(f"  OK   valid/{name}/{fixture.name}")
            except Invalid as e:
                print(f"  FAIL valid/{name}/{fixture.name}: {e}")
                failures += 1

        for fixture in sorted((vectors / "invalid").glob("*.json")):
            try:
                validate(json.loads(fixture.read_text()), schema)
                print(f"  FAIL invalid/{name}/{fixture.name}: validated but must be rejected")
                failures += 1
            except Invalid as e:
                print(f"  OK   invalid/{name}/{fixture.name} rejected ({e})")

        valid_count = len(list((vectors / "valid").glob("*.json")))
        invalid_count = len(list((vectors / "invalid").glob("*.json")))
        if valid_count == 0 or invalid_count == 0:
            print(f"  FAIL {name}: needs at least one valid and one invalid fixture")
            failures += 1

    if failures:
        print(f"protocol fixtures: {failures} failure(s)", file=sys.stderr)
        return 1
    print("protocol fixtures: all green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
