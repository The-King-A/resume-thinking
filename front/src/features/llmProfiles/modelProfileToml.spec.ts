import { describe, expect, it } from 'vitest'
import { parseModelProfileToml, serializeModelProfileToml } from './modelProfileToml'

const validToml = `[profile]
display_name = "DeepSeek"
endpoint_url = "https://api.deepseek.com/v1"
model_name = "deepseek-chat"
selected = true
`

describe('model profile TOML mapping', () => {
  it('maps the approved snake-case profile projection', () => {
    expect(parseModelProfileToml(validToml)).toEqual({
      displayName: 'DeepSeek',
      endpointUrl: 'https://api.deepseek.com/v1',
      modelName: 'deepseek-chat',
      selected: true,
    })
  })

  it.each([
    ['malformed TOML', '[profile\ndisplay_name = "DeepSeek"'],
    ['a second section', `${validToml}\n[provider]\ntimeout = 30`],
    ['an unknown field', `${validToml}\n[profile]\nunknown = "value"`],
    ['an api_key field', `${validToml}\n[profile]\napi_key = "must-not-be-accepted"`],
    ['a non-boolean selected value', validToml.replace('selected = true', 'selected = "true"')],
    ['an inline profile table', 'profile = { display_name = "DeepSeek", endpoint_url = "https://api.deepseek.com/v1", model_name = "deepseek-chat", selected = true }'],
    ['dotted profile assignments', 'profile.display_name = "DeepSeek"\nprofile.endpoint_url = "https://api.deepseek.com/v1"\nprofile.model_name = "deepseek-chat"\nprofile.selected = true'],
  ])('rejects %s instead of silently discarding it', (_reason, text) => {
    expect(() => parseModelProfileToml(text)).toThrow('TOML')
  })

  it('serializes only the approved non-secret projection', () => {
    const text = serializeModelProfileToml({
      displayName: 'DeepSeek',
      endpointUrl: 'https://api.deepseek.com/v1',
      modelName: 'deepseek-chat',
      selected: true,
    })

    expect(text).toContain('[profile]')
    expect(text).toContain('display_name = "DeepSeek"')
    expect(text).not.toContain('api_key')
  })
})
