// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { normalizeMatchResult } from './lifecycle'

describe('normalizeMatchResult', () => {
  it('maps a Java evidenceId compatibility field to the frozen contract id', () => {
    const result = normalizeMatchResult({
      taskId: 'task-1', resumeId: 'resume-1', resumeVersion: 1, jobDescriptionText: 'Java backend role requirement text',
      score: { skills: 0, projectExperience: 0, workContent: 0, educationExperience: 0, softSkills: 0, composite: 0 },
      requirements: [{ requirementId: 'requirement-1', requirementText: 'Redis', requirementType: 'MANDATORY', matchStatus: 'UNMET', matchType: 'NO_MATCH', component: 'SKILLS', componentScore: 0, evidence: [{ evidenceId: 'evidence-java-1', sourceType: 'TXT', sourceLocation: 'Skills', excerpt: 'Redis', confidence: 0.5, strength: 'LOW' }], gap: 'Missing evidence', suggestionState: 'RISKY_OR_UNSUPPORTED' }],
      suggestions: [], completedAt: '2026-08-27T08:00:00Z',
    })

    expect(result.requirements[0]?.evidence[0]?.id).toBe('evidence-java-1')
  })
})
