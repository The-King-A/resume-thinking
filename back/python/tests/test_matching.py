import pytest

from app.matching import composite_score


def test_composite_score_uses_documented_weights():
    score = composite_score(skills=1.0, projects=0.8, work_content=0.6, education_experience=0.5, soft_skills=0.4)
    assert score == pytest.approx(0.40 + 0.20 + 0.09 + 0.05 + 0.04)


def test_composite_score_uses_half_up_for_ties_like_java():
    # The exact decimal weighted sum is 0.17555.  Python's binary float can
    # represent this just below the tie, while Java's contract uses HALF_UP.
    score = composite_score(skills=0.3, projects=0, work_content=0, education_experience=0, soft_skills=0.5555)

    assert score == 0.1756
