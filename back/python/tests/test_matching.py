import pytest

from app.matching import composite_score


def test_composite_score_uses_documented_weights():
    score = composite_score(skills=1.0, projects=0.8, work_content=0.6, education_experience=0.5, soft_skills=0.4)
    assert score == pytest.approx(0.40 + 0.20 + 0.09 + 0.05 + 0.04)
