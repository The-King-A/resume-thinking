// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RecoveryDialog from './RecoveryDialog.vue'

describe('RecoveryDialog', () => {
  it('renders Chinese recovery copy and preserves the restore payload', async () => {
    const wrapper = mount(RecoveryDialog, {
      props: { open: true, title: '后端简历', version: 4 },
    })

    expect(wrapper.text()).toContain('恢复简历')
    expect(wrapper.text()).toContain('后端简历')
    expect(wrapper.text()).toContain('取消')
    expect(wrapper.text()).toContain('恢复简历')

    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[{ expectedVersion: 4 }]])
  })
})
