"""Checks that invalid/restarted samples cannot produce misleading CPU results."""
import unittest
from validate_paisagens import deltas, parse


class MetricsTests(unittest.TestCase):
    def test_cpu_excludes_guest_and_process_uses_one_core(self):
        before = dict(cpu=[100, 0, 100, 800, 0, 0, 0, 0], ticks=50, pid="42", frames=10)
        after = dict(cpu=[120, 0, 110, 870, 0, 0, 0, 0], ticks=55, pid="42", frames=12)
        self.assertEqual(deltas(after, before, 4),
                         dict(cpu_total_pct=30.0, lume_cpu_one_core_pct=20.0, frames_delta=2))

    def test_restart_does_not_look_idle(self):
        before = dict(cpu=[1, 0, 1, 10, 0, 0, 0, 0], ticks=500, pid="42", frames=100)
        after = dict(cpu=[2, 0, 2, 20, 0, 0, 0, 0], ticks=1, pid="43", frames=1)
        self.assertIsNone(deltas(after, before, 4)["lume_cpu_one_core_pct"])
        self.assertIsNone(deltas(after, before, 4)["frames_delta"])

    def test_thermal_uses_current_not_cached_and_no_other_app_video(self):
        r = parse("@@thermal\nCached temperatures:\nTemperature{mValue=30.0, mType=0, mName=soc_thermal}\n"
                  "Current temperatures from HAL:\nTemperature{mValue=53.4, mType=0, mName=soc_thermal}\n"
                  "@@player\npackageName: other.app\nresolution(3840 x 2160)\n")
        self.assertEqual(r["temp_c"], 53.4)
        self.assertIsNone(r["video_resolution"])
        self.assertIsNone(r["pss_mb"])

    def test_guest_fields_not_double_counted(self):
        r = parse("@@cpu\ncpu 1 2 3 4 5 6 7 8 999 999\n@@uptime\n123.0 400.0\n")
        self.assertEqual(len(r["cpu"]), 8)
        self.assertEqual(sum(r["cpu"]), 36)

    def test_multiple_players_selects_lume(self):
        r = parse("@@player\n Client\npackageName: other.app, tag: null\nresolution(1920 x 1080)\n"
                  " Client\npackageName: br.modtv.launcher, tag: null\nresolution(3840 x 2160)\nmPaused:1\n")
        self.assertEqual(r["video_resolution"], "3840 x 2160")
        self.assertEqual(r["video_paused"], 1)


if __name__ == "__main__":
    unittest.main()
