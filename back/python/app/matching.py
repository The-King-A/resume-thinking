from __future__ import annotations

from .models import ScoreBreakdown


def composite_score(*, skills: float, projects: float, work_content: float, education_experience: float, soft_skills: float) -> float:
    return round(0.40 * skills + 0.25 * projects + 0.15 * work_content + 0.10 * education_experience + 0.10 * soft_skills, 4)


def score_breakdown(*, skills: float, projects: float, work_content: float, education_experience: float, soft_skills: float) -> ScoreBreakdown:
    return ScoreBreakdown(skills=skills, projectExperience=projects, workContent=work_content, educationExperience=education_experience, softSkills=soft_skills, composite=composite_score(skills=skills, projects=projects, work_content=work_content, education_experience=education_experience, soft_skills=soft_skills))
