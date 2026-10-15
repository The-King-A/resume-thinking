declare module '@iarna/toml/parse-string' {
  const parse: (toml: string) => Record<string, unknown>
  export default parse
}

declare module '@iarna/toml/stringify' {
  const stringify: (document: Record<string, unknown>) => string
  export default stringify
}
