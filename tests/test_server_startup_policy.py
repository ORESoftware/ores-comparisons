from __future__ import annotations

import copy
import unittest

from scripts.verify_server_startup_policy import admit_startup, load_contract, resolve_value, verify_contract

class ServerStartupPolicyTest(unittest.TestCase):
    def test_contract_is_coherent(self) -> None:
        self.assertEqual(verify_contract(load_contract()), [])

    def test_flags_override_env_encrypted_and_defaults(self) -> None:
        value, source = resolve_value(
            "bind-address",
            cli={"bind-address":"cli"},
            env={"bind-address":"env"},
            encrypted={"bind-address":"enc"},
            defaults={"bind-address":"default"},
        )
        self.assertEqual((value,source),("cli","cli-flags"))

    def test_env_overrides_encrypted_and_defaults(self) -> None:
        value, source = resolve_value(
            "auth-policy", cli={}, env={"auth-policy":"env"},
            encrypted={"auth-policy":"enc"}, defaults={"auth-policy":"default"},
        )
        self.assertEqual((value,source),("env","environment"))

    def valid(self) -> dict:
        return {
            "resolved":{
                "bind-address":"127.0.0.1:3000",
                "deployment-mode":"standalone",
                "auth-policy":"required",
            },
            "sources":{"bind-address":"environment"},
            "deployment":{"mode":"standalone","public":False},
            "security":{"auth-policy":"required","tenant-mode":"strict","database-scope":"write","admin-enabled":False},
        }

    def test_missing_required_setting_fails_startup(self) -> None:
        value=self.valid(); value["resolved"].pop("auth-policy")
        self.assertIn("missing required setting: auth-policy",admit_startup(value))

    def test_unknown_security_field_fails_closed(self) -> None:
        value=self.valid(); value["security"]["trust-everything"]=True
        self.assertTrue(any("unknown security-sensitive fields" in e for e in admit_startup(value)))

    def test_contradictory_deployment_mode_fails(self) -> None:
        value=self.valid(); value["deployment"]={"mode":"aws-lambda-http","public":True,"longLivedServer":True}
        self.assertIn("contradictory deployment mode",admit_startup(value))

    def test_public_admin_fails(self) -> None:
        value=self.valid(); value["deployment"]["public"]=True; value["security"]["admin-enabled"]=True
        self.assertIn("admin enabled on public deployment",admit_startup(value))

if __name__=="__main__":
    unittest.main()
