export type AppSummary = {
  id: string
  title: string
  subtitle: string
  bundleId: string
  version: string
  build: string
  uploadedAt: string
  signedUntil?: string
  iconUrl: string
  installUrl: string
}

async function checked(response: Response): Promise<Response> {
  if (response.ok) return response
  const body = (await response.json().catch(() => null)) as { error?: string } | null
  throw new Error(body?.error ?? `Request failed (${response.status})`)
}

export async function getApps(): Promise<AppSummary[]> {
  return checked(await fetch('/api/apps')).then((response) => response.json())
}

export async function getInstructions(): Promise<string> {
  return checked(await fetch('/instructions.md')).then((response) => response.text())
}

export async function publishApp(form: FormData): Promise<AppSummary> {
  return checked(await fetch('/api/apps', { method: 'POST', body: form })).then((response) => response.json())
}

