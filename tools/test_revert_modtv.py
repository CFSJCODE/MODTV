import unittest
from revert_modtv import plan, check, HOME, DREAM, VOICE, PREEXISTING_DISABLED


class RollbackTests(unittest.TestCase):
    def test_plan_has_no_destructive_commands_and_preserves_initial_disabled(self):
        for mode in ("home", "full"):
            cmds = [cmd for _, cmd in plan(mode)]
            for cmd in cmds:
                self.assertFalse(any(p in cmd for p in PREEXISTING_DISABLED))
                self.assertFalse(any(x in cmd for x in ("uninstall", "pm clear", "reboot", "rm ")))

    def test_lume_is_disabled_only_after_original_home(self):
        cmds = [cmd for _, cmd in plan("full")]
        self.assertGreater(cmds.index("pm disable-user --user 0 br.modtv.launcher"),
                           cmds.index("cmd package set-home-activity --user 0 " + HOME))
        self.assertFalse(any("disable-user" in cmd for _, cmd in plan("home")))

    def test_refresh_notify_before_delete(self):
        cmds = [cmd for _, cmd in plan("full")]
        self.assertLess(cmds.index("settings put secure match_content_frame_rate 1"),
                        cmds.index("settings delete secure match_content_frame_rate"))

    def test_validation_catches_home_and_refresh_failures(self):
        snapshot = {"home": HOME, "secure.screensaver_components": DREAM,
                    "disabled": "package:br.modtv.launcher", "secure.assistant": VOICE,
                    "secure.voice_interaction_service": VOICE,
                    "system.peak_refresh_rate": "null", "system.min_refresh_rate": "null",
                    "secure.match_content_frame_rate": "null"}
        self.assertEqual(check(snapshot, "full"), [])
        snapshot["home"] = "br.modtv.launcher/.HomeActivity"
        snapshot["system.min_refresh_rate"] = "120.0"
        self.assertEqual(len(check(snapshot, "full")), 2)


if __name__ == "__main__":
    unittest.main()
