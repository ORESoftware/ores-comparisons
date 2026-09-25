from __future__ import annotations

import unittest

from scripts.verify_contract_compatibility import compare_projection, compare_schema


class ContractCompatibilityTests(unittest.TestCase):
    def test_additive_schema_change_is_allowed(self) -> None:
        before = {
            "$defs": {
                "Thing": {
                    "type": "object",
                    "properties": {"id": {"type": "string"}},
                    "required": ["id"],
                    "unevaluatedProperties": False,
                }
            }
        }
        after = {
            "$defs": {
                "Thing": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                    },
                    "required": ["id"],
                    "unevaluatedProperties": False,
                }
            }
        }
        self.assertEqual([], compare_schema(before, after, "project"))

    def test_required_property_addition_is_breaking(self) -> None:
        before = {
            "$defs": {
                "Thing": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                    },
                    "required": ["id"],
                }
            }
        }
        after = {
            "$defs": {
                "Thing": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                    },
                    "required": ["id", "label"],
                }
            }
        }
        codes = {finding.code for finding in compare_schema(before, after, "project")}
        self.assertIn("schema-required-added", codes)

    def test_enum_value_removal_is_breaking(self) -> None:
        before = {"$defs": {"State": {"type": "string", "enum": ["open", "closed"]}}}
        after = {"$defs": {"State": {"type": "string", "enum": ["open"]}}}
        codes = {finding.code for finding in compare_schema(before, after, "project")}
        self.assertIn("schema-enum-value-removed", codes)

    def test_constraint_tightening_is_breaking(self) -> None:
        before = {"$defs": {"Name": {"type": "string", "minLength": 1, "maxLength": 128}}}
        after = {"$defs": {"Name": {"type": "string", "minLength": 4, "maxLength": 64}}}
        findings = compare_schema(before, after, "project")
        self.assertEqual(2, sum(finding.code == "schema-constraint-tightened" for finding in findings))

    def test_sql_column_removal_and_nullability_tightening_are_breaking(self) -> None:
        before = {
            "tables": [
                {
                    "name": "things",
                    "primary_key": "id",
                    "columns": [
                        {"name": "id", "source": "id", "sql_type": "TEXT"},
                        {"name": "label", "source": "label", "sql_type": "TEXT", "nullable": True},
                        {"name": "legacy", "source": "legacy", "sql_type": "TEXT"},
                    ],
                }
            ],
            "proto": {"package": "x.v1", "service": "Api", "messages": [], "rpcs": []},
            "interfaces": [],
        }
        after = {
            "tables": [
                {
                    "name": "things",
                    "primary_key": "id",
                    "columns": [
                        {"name": "id", "source": "id", "sql_type": "TEXT"},
                        {"name": "label", "source": "label", "sql_type": "TEXT", "nullable": False},
                    ],
                }
            ],
            "proto": {"package": "x.v1", "service": "Api", "messages": [], "rpcs": []},
            "interfaces": [],
        }
        codes = {finding.code for finding in compare_projection(before, after, "project")}
        self.assertIn("sql-column-removed", codes)
        self.assertIn("sql-column-nullability-tightened", codes)

    def test_protobuf_field_number_reuse_is_breaking(self) -> None:
        before = {
            "tables": [],
            "proto": {
                "package": "x.v1",
                "service": "Api",
                "messages": [
                    {"name": "Request", "fields": [{"name": "id", "type": "string", "number": 1}]}
                ],
                "rpcs": [],
            },
            "interfaces": [],
        }
        after = {
            "tables": [],
            "proto": {
                "package": "x.v1",
                "service": "Api",
                "messages": [
                    {"name": "Request", "fields": [{"name": "tenant_id", "type": "string", "number": 1}]}
                ],
                "rpcs": [],
            },
            "interfaces": [],
        }
        codes = {finding.code for finding in compare_projection(before, after, "project")}
        self.assertIn("protobuf-field-number-reused", codes)

    def test_additive_projection_changes_are_allowed(self) -> None:
        before = {
            "tables": [
                {
                    "name": "things",
                    "primary_key": "id",
                    "columns": [{"name": "id", "source": "id", "sql_type": "TEXT"}],
                }
            ],
            "proto": {
                "package": "x.v1",
                "service": "Api",
                "messages": [{"name": "Request", "fields": [{"name": "id", "type": "string", "number": 1}]}],
                "rpcs": [],
            },
            "interfaces": [{"model": "Thing"}],
        }
        after = {
            "tables": [
                {
                    "name": "things",
                    "primary_key": "id",
                    "columns": [
                        {"name": "id", "source": "id", "sql_type": "TEXT"},
                        {"name": "label", "source": "label", "sql_type": "TEXT", "nullable": True},
                    ],
                }
            ],
            "proto": {
                "package": "x.v1",
                "service": "Api",
                "messages": [
                    {
                        "name": "Request",
                        "fields": [
                            {"name": "id", "type": "string", "number": 1},
                            {"name": "label", "type": "string", "number": 2},
                        ],
                    }
                ],
                "rpcs": [],
            },
            "interfaces": [{"model": "Thing"}, {"model": "Other"}],
        }
        self.assertEqual([], compare_projection(before, after, "project"))


if __name__ == "__main__":
    unittest.main()
