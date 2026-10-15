from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP

from .models import ScoreBreakdown


def composite_score(*, skills: float, projects: float, work_content: float, education_experience: float, soft_skills: float) -> float:
    weighted = (
        Decimal(str(skills)) * Decimal("0.40")
        + Decimal(str(projects)) * Decimal("0.25")
        + Decimal(str(work_content)) * Decimal("0.15")
        + Decimal(str(education_experience)) * Decimal("0.10")
        + Decimal(str(soft_skills)) * Decimal("0.10")
    )
    return float(weighted.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))


def score_breakdown(*, skills: float, projects: float, work_content: float, education_experience: float, soft_skills: float) -> ScoreBreakdown:
    return ScoreBreakdown(skills=skills, projectExperience=projects, workContent=work_content, educationExperience=education_experience, softSkills=soft_skills, composite=composite_score(skills=skills, projects=projects, work_content=work_content, education_experience=education_experience, soft_skills=soft_skills))
