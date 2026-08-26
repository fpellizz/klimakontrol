import unittest
from datetime import datetime

from klimakontrol import tasks as T


class TestTimezoneTrap(unittest.TestCase):
    """Il firmware vive in UTC+8: senza conversione i timer scattano a caso."""

    def test_italian_summer_time_shifts_six_hours(self):
        local = datetime(2026, 8, 20, 22, 0, 0)          # 22:00, UTC+2
        self.assertEqual(T.to_device_time(local, 2).hour, 4)

    def test_italian_winter_time_shifts_seven_hours(self):
        local = datetime(2026, 1, 15, 22, 0, 0)          # 22:00, UTC+1
        self.assertEqual(T.to_device_time(local, 1).hour, 5)

    def test_device_local_round_trip(self):
        local = datetime(2026, 8, 20, 7, 30, 0)
        for offset in (-5, 0, 1, 2, 5.5, 8):
            self.assertEqual(T.from_device_time(T.to_device_time(local, offset), offset), local)

    def test_no_shift_when_client_is_in_device_timezone(self):
        local = datetime(2026, 8, 20, 9, 0, 0)
        self.assertEqual(T.to_device_time(local, 8), local)

    def test_shift_can_cross_midnight(self):
        local = datetime(2026, 8, 20, 23, 30, 0)
        moved = T.to_device_time(local, 2)
        self.assertEqual((moved.day, moved.hour, moved.minute), (21, 5, 30))


class TestTimeFormat(unittest.TestCase):
    def test_once_and_delay_carry_the_date(self):
        self.assertEqual(T.time_format(T.TYPE_ONCE), T.DATETIME_FMT)
        self.assertEqual(T.time_format(T.TYPE_DELAY), T.DATETIME_FMT)

    def test_recurring_types_carry_only_the_time(self):
        for t in (T.TYPE_PERIOD, T.TYPE_CYCLE, T.TYPE_RANDOM):
            self.assertEqual(T.time_format(t), T.TIME_FMT)


class TestTaskSerialisation(unittest.TestCase):
    def test_weekday_repeat_list(self):
        # 07:00 +6h = 13:00 stesso giorno (nessuno shift). repeat = giorni 1..7 (lun..dom).
        task = T.Task(type=T.TYPE_PERIOD, time=datetime(2026, 8, 20, 7, 0),
                      weekday=[0, 1, 2, 3, 4])
        self.assertEqual(task.to_wire(tz_offset=2)["repeat"], [1, 2, 3, 4, 5])

    def test_weekend_repeat_list(self):
        task = T.Task(type=T.TYPE_PERIOD, time=datetime(2026, 8, 20, 7, 0),
                      weekday=[5, 6])
        self.assertEqual(task.to_wire(tz_offset=2)["repeat"], [6, 7])

    def test_repeat_shifts_a_day_when_conversion_crosses_midnight(self):
        # 22:00 +6h = 04:00 del giorno dopo: i giorni scalano di +1 (come updateWeek).
        task = T.Task(type=T.TYPE_PERIOD, time=datetime(2026, 8, 20, 22, 0), weekday=[0])
        self.assertEqual(task.to_wire(tz_offset=2)["repeat"], [2])   # lun locale -> mar device

    def test_status_is_encoded_in_dna_shape(self):
        task = T.Task(type=T.TYPE_ONCE, time=datetime(2026, 8, 20, 22, 0),
                      status={"pwr": 1, "temp": 210})
        wire = task.to_wire(tz_offset=2)
        self.assertEqual(wire["data"]["params"], ["pwr", "temp"])
        self.assertEqual(wire["data"]["vals"][1], [{"val": 210, "idx": 1}])

    def test_round_trip_preserves_meaning(self):
        task = T.Task(type=T.TYPE_PERIOD, time=datetime(2026, 8, 20, 22, 0),
                      weekday=[0, 2, 4], status={"pwr": 1, "temp": 210, "tcl_mode": 1})
        back = T.Task.from_wire(task.to_wire(tz_offset=2), T.TYPE_PERIOD, tz_offset=2)
        self.assertEqual(back.time.strftime("%H:%M"), "22:00")
        self.assertEqual(back.weekday, [0, 2, 4])
        self.assertEqual(back.status, {"pwr": 1, "temp": 210, "tcl_mode": 1})

    def test_cycle_type_carries_second_command(self):
        task = T.Task(type=T.TYPE_CYCLE, time=datetime(2026, 8, 20, 8, 0),
                      status={"pwr": 1}, status2={"pwr": 0})
        wire = task.to_wire(tz_offset=2)
        self.assertIn("data2", wire)
        self.assertEqual(wire["data2"]["params"], ["pwr"])

    def test_describe_is_human_readable(self):
        task = T.Task(type=T.TYPE_PERIOD, time=datetime(2026, 8, 20, 22, 0),
                      weekday=[0], status={"pwr": 1, "temp": 210})
        text = task.describe()
        self.assertIn("ricorrente", text)
        self.assertIn("22:00", text)
        self.assertIn("lun", text)
        self.assertIn("21.0", text)


class TestTaskListParsing(unittest.TestCase):
    def test_all_five_lists_are_read(self):
        response = {"data": {
            "timerlist": [{"index": 0, "time": "2026-08-21 04:00:00", "enable": 1}],
            "delaylist": [],
            "periodlist": [{"index": 1, "time": "04:00:00", "repeat": [3], "enable": 1}],
            "cyclelist": [],
            "randomlist": [],
        }}
        tasks = T.parse_task_list(response, tz_offset=2)
        self.assertEqual(len(tasks), 2)
        kinds = sorted(t.type for t in tasks)
        self.assertEqual(kinds, [T.TYPE_ONCE, T.TYPE_PERIOD])

    def test_periodic_time_is_converted_back_to_local(self):
        # device: 04:00 mar (repeat [2]) -> locale 22:00 lun ([0]) con lo shift inverso
        response = {"data": {"periodlist": [{"index": 0, "time": "04:00:00",
                                             "repeat": [2], "enable": 1}]}}
        task = T.parse_task_list(response, tz_offset=2)[0]
        self.assertEqual(task.time.strftime("%H:%M"), "22:00")
        self.assertEqual(task.weekday, [0])


if __name__ == "__main__":
    unittest.main()
