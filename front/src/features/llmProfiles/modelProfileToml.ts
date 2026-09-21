import parse from '@iarna/toml/parse-string'
import stringify from '@iarna/toml/stringify'

export interface ModelProfileToml {
  displayName: string
  endpointUrl: string
  modelName: string
  selected: boolean
}

const profileFields = ['display_name', 'endpoint_url', 'model_name', 'selected'] as const

function invalidToml(): never {
  throw new Error('TOML 配置格式无效。')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isDeclaredProfileTable(value: Record<string, unknown>) {
  return Object.getOwnPropertySymbols(value).some((symbol) => symbol.description === 'declared' && Reflect.get(value, symbol) === true)
}

export function parseModelProfileToml(text: string): ModelProfileToml {
  let document: unknown
  try {
    document = parse(text)
  } catch {
    return invalidToml()
  }

  if (!isRecord(document) || Object.keys(document).length !== 1 || !Object.hasOwn(document, 'profile')) {
    return invalidToml()
  }

  const profile = document.profile
  if (!isRecord(profile) || !isDeclaredProfileTable(profile)) return invalidToml()

  const keys = Object.keys(profile)
  if (keys.length !== profileFields.length || keys.some((key) => !profileFields.includes(key as typeof profileFields[number]))) {
    return invalidToml()
  }

  const { display_name: displayName, endpoint_url: endpointUrl, model_name: modelName, selected } = profile
  if (typeof displayName !== 'string' || typeof endpointUrl !== 'string' || typeof modelName !== 'string' || typeof selected !== 'boolean') {
    return invalidToml()
  }

  return { displayName, endpointUrl, modelName, selected }
}

export function serializeModelProfileToml(profile: ModelProfileToml): string {
  return stringify({
    profile: {
      display_name: profile.displayName,
      endpoint_url: profile.endpointUrl,
      model_name: profile.modelName,
      selected: profile.selected,
    },
  })
}
