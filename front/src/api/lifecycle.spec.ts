// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { normalizeMatchResult } from './lifecycle'
import type { MatchResult } from './contracts'

describe('normalizeMatchResult', () => {
  const baseResult: MatchResult = {
      taskId: 'task001', resumeId: 'resume001', resumeVersion: 1, jobDescriptionText: 'Java backend role requirement text',
      score: { skills: 0, projectExperience: 0, workContent: 0, educationExperience: 0, softSkills: 0, composite: 0 },
      requirements: [{ requirementId: 'requirement001', requirementText: 'Redis', requirementType: 'MANDATORY', matchStatus: 'UNMET', matchType: 'NO_MATCH', component: 'SKILLS', componentScore: 0, evidence: [{ id: 'evidence001', sourceType: 'TXT', sourceLocation: 'Skills', excerpt: 'Redis', confidence: 0.5, strength: 'LOW' }], gap: 'Missing evidence', suggestionState: 'RISKY_OR_UNSUPPORTED' }],
      suggestions: [], completedAt: '2026-08-27T08:00:00Z',
  }

  it('preserves v2 evidence with its required id', () => {
    const result = normalizeMatchResult(baseResult)

    expect(result.requirements[0]?.evidence[0]?.id).toBe('evidence001')
  })

  it('rejects the non-v2 evidenceId compatibility field', () => {
    const malformed = {
      ...baseResult,
      requirements: [{ ...baseResult.requirements[0], evidence: [{ evidenceId: 'evidence-java-1', sourceType: 'TXT', sourceLocation: 'Skills', excerpt: 'Redis', confidence: 0.5, strength: 'LOW' }] }],
    }

    expect(() => normalizeMatchResult(malformed as never)).toThrow('Evidence must include a v2 id')
  })

  it('rejects evidence without the required id', () => {
    const malformed = {
      ...baseResult,
      requirements: [{ ...baseResult.requirements[0], evidence: [{ sourceType: 'TXT', sourceLocation: 'Skills', excerpt: 'Redis', confidence: 0.5, strength: 'LOW' }] }],
    }

    expect(() => normalizeMatchResult(malformed as never)).toThrow('Evidence must include a v2 id')
  })
})
