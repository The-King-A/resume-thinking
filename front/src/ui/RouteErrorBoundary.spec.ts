// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import RouteErrorBoundary from './RouteErrorBoundary.vue'

const ThrowingRoute = {
  setup() {
    throw new Error('route render failed')
  },
}

describe('RouteErrorBoundary', () => {
  it('keeps a failed route from leaving the signed-in workspace blank', async () => {
    const wrapper = mount(RouteErrorBoundary, {
      global: {
        stubs: {
          RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        },
      },
      slots: {
        default: () => h(ThrowingRoute),
      },
    })

    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('页面暂时无法加载')
    expect(wrapper.text()).toContain('登录状态仍然保留')
    expect(wrapper.get('a').attributes('href')).toBe('/resumes')
  })
})
