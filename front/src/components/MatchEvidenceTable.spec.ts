// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MatchEvidenceTable from './MatchEvidenceTable.vue'

describe('MatchEvidenceTable', () => {
  it('shows every evidence field and keeps insufficient evidence non-positive', () => {
    const wrapper = mount(MatchEvidenceTable, { props: { requirements: [{
      requirementId: 'requirement001', requirementText: 'Production Redis experience', requirementType: 'MANDATORY',
      matchStatus: 'RELATED_BUT_EVIDENCE_INSUFFICIENT', matchType: 'RELATED', component: 'WORK_CONTENT', componentScore: 0.35,
      evidence: [{ id: 'evidence001', sourceType: 'DOCX', sourceLocation: 'Projects / paragraph 2', sourceStart: 120, sourceEnd: 164, excerpt: 'Used a cache in a course project', confidence: 0.62, strength: 'LOW' }],
      gap: 'No production context is evidenced.', suggestionState: 'NEEDS_USER_CONFIRMATION',
    }] } })

    for (const heading of ['岗位要求', '简历证据', '位置', '匹配类型', '得分', '证据强度', '差距']) expect(wrapper.text()).toContain(heading)
    expect(wrapper.text()).toContain('Production Redis experience')
    expect(wrapper.text()).toContain('requirement001')
    expect(wrapper.text()).toContain('Used a cache in a course project')
    expect(wrapper.text()).toContain('Projects / paragraph 2')
    expect(wrapper.text()).toContain('DOCX')
    expect(wrapper.text()).toContain('evidence001')
    expect(wrapper.text()).toContain('字符 120-164')
    expect(wrapper.text()).toContain('35%')
    expect(wrapper.get('tbody tr').attributes('data-positive')).toBe('false')
  })
})
