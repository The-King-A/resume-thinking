// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelProfileForm from './ModelProfileForm.vue'

describe('ModelProfileForm', () => {
  it('never renders a saved API key', () => {
    const wrapper = mount(ModelProfileForm, {
      props: {
        profile: {
          id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com', modelName: 'gpt',
          hasApiKey: true, selected: false, createdAt: '', updatedAt: '',
        },
      },
    })
    expect(wrapper.text()).not.toContain('secret-api-key')
  })
})
