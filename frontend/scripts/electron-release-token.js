export function extractGithubTokenFromCredentialFill(output) {
  const text = typeof output === 'string' ? output : ''
  const match = text.match(/^password=(.+)$/m)
  return match?.[1]?.trim() || ''
}

export function resolveGithubToken({ env = process.env, credentialFillOutput = '' } = {}) {
  const envToken = typeof env?.GH_TOKEN === 'string' ? env.GH_TOKEN.trim() : ''
  if (envToken) return envToken
  return extractGithubTokenFromCredentialFill(credentialFillOutput)
}
