const API = '/api/v1'

export function getToken(): string | null {
  return localStorage.getItem('contextflow_token')
}

export function setToken(token: string) {
  localStorage.setItem('contextflow_token', token)
}

export function clearToken() {
  localStorage.removeItem('contextflow_token')
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function apiFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, {
    ...options,
    headers: { ...authHeaders(), ...(options.headers as Record<string, string>) },
  })
  if (res.status === 401) {
    clearToken()
    window.location.href = '/'
    throw new Error('Session expired')
  }
  return res
}

// ── Auth ─────────────────────────────────────────────────────────────────────

export interface TokenResponse {
  accessToken: string
  refreshToken: string
}

export async function login(email: string, password: string): Promise<TokenResponse> {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? 'Invalid credentials')
  }
  return res.json()
}

export async function register(
  organizationName: string,
  slug: string,
  adminEmail: string,
  adminPassword: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ organizationName, slug, adminEmail, adminPassword }),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? 'Registration failed')
  }
  return res.json()
}

export async function logout(): Promise<void> {
  const token = getToken()
  if (token) {
    await fetch(`${API}/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    }).catch(() => {})
  }
  clearToken()
}

// ── Documents ────────────────────────────────────────────────────────────────

export interface UploadResponse {
  jobId: string
  documentId: string
  originalFilename: string
  fileSizeBytes: number
  status: string
}

export interface JobStatus {
  jobId: string
  documentId: string
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
  originalFilename: string
  errorMessage?: string
}

export interface Document {
  id: string
  jobId: string
  originalFilename: string
  fileSizeBytes: number
  contentType: string
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
  createdAt: string
}

export async function uploadDocument(file: File): Promise<UploadResponse> {
  const form = new FormData()
  form.append('file', file)
  const res = await apiFetch(`${API}/documents/upload`, { method: 'POST', body: form })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? 'Upload failed')
  }
  return res.json()
}

export async function getJobStatus(jobId: string): Promise<JobStatus> {
  const res = await apiFetch(`${API}/documents/jobs/${jobId}/status`)
  if (!res.ok) throw new Error('Status check failed')
  return res.json()
}

export async function listDocuments(): Promise<Document[]> {
  const res = await apiFetch(`${API}/documents?page=0&size=50`)
  if (!res.ok) throw new Error('Failed to list documents')
  const page = await res.json()
  // Spring Page response shape: { content: [...], ... }
  return (page.content ?? page) as Document[]
}

// ── Query / SSE ───────────────────────────────────────────────────────────────

export interface Source {
  filename: string
  page: number
  snippet: string
  similarity: number
}

export interface SSEEvent {
  name: string
  data: string
}

async function* parseSSEStream(
  response: Response,
  signal: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE events are separated by blank lines (\n\n or \r\n\r\n)
      const boundary = /\r?\n\r?\n/
      let match: RegExpExecArray | null
      while ((match = boundary.exec(buffer)) !== null) {
        const raw = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)

        if (!raw.trim()) continue
        let name = 'message'
        const dataLines: string[] = []
        for (const line of raw.split(/\r?\n/)) {
          if (line.startsWith('event:')) name = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (dataLines.length) yield { name, data: dataLines.join('\n') }
      }
    }
  } finally {
    reader.releaseLock()
  }
}

export async function* streamQuery(
  question: string,
  signal: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const res = await fetch(`${API}/query`, {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ question }),
    signal,
  })

  if (res.status === 401) {
    clearToken()
    window.location.href = '/'
    return
  }
  if (!res.ok) throw new Error('Query failed')
  yield* parseSSEStream(res, signal)
}
