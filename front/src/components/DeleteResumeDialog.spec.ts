// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DeleteResumeDialog from './DeleteResumeDialog.vue'

describe('DeleteResumeDialog', () => {
  it('renders the deletion confirmation copy in Chinese', () => {
    const wrapper = mount(DeleteResumeDialog, {
      props: { open: true, resumeId: 'r-1', version: 2 },
    })

    expect(wrapper.text()).toContain('谨慎删除')
    expect(wrapper.text()).toContain('删除这份简历？')
    expect(wrapper.text()).toContain('请输入')
    expect(wrapper.text()).toContain('取消')
    expect(wrapper.text()).toContain('删除简历')
  })

  it('keeps delete disabled until the exact confirmation phrase is entered', async () => {
    const wrapper = mount(DeleteResumeDialog, {
      props: { open: true, resumeId: 'r-1', version: 2 },
    })

    await wrapper.get('input').setValue('确认删除')
    expect(wrapper.get('[data-test="confirm-delete"]').attributes('disabled')).toBeDefined()

    await wrapper.get('input').setValue('确认删除简历')
    expect(wrapper.get('[data-test="confirm-delete"]').attributes('disabled')).toBeUndefined()
  })

  it('submits only the required confirmation and optimistic-lock version', async () => {
    const wrapper = mount(DeleteResumeDialog, {
      props: { open: true, resumeId: 'r-1', version: 2 },
    })

    await wrapper.get('input').setValue('确认删除简历')
    await wrapper.get('[data-test="confirm-delete"]').trigger('click')

    expect(wrapper.emitted('confirm')).toEqual([[
      { confirmationText: '确认删除简历', expectedVersion: 2 },
    ]])
  })
})
