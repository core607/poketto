import unittest
from unittest.mock import patch

import review


class RoundBudgetTests(unittest.TestCase):
    def test_weekday_peak_boundaries_use_beijing_instead_of_runner_timezone(self):
        for weekday in range(7):
            for hour, minute, weekday_peak in ((8, 59, False), (9, 0, True), (11, 59, True),
                                               (12, 0, False), (13, 59, False), (14, 0, True),
                                               (17, 59, True), (18, 0, False), (23, 59, False)):
                local = review.datetime(2026, 9, 7 + weekday, hour, minute, tzinfo=review.BEIJING)
                with self.subTest(local=local), patch.object(review, "beijing_now", return_value=local.astimezone(review.timezone.utc)):
                    budget = review.RoundBudget(30)
                    self.assertEqual(3 if weekday < 5 and weekday_peak else 30, budget.limit)

    def test_peak_never_increases_a_smaller_remaining_budget(self):
        with patch.object(review, "beijing_now", return_value=review.datetime(2026, 9, 9, 13, tzinfo=review.BEIJING)):
            budget = review.RoundBudget(30)
        budget.used = 29
        with patch.object(review, "beijing_now", return_value=review.datetime(2026, 9, 9, 14, tzinfo=review.BEIJING)):
            self.assertEqual(1, budget.refresh()["remaining_turns"])
