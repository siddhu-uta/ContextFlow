import { useEffect, useRef, useState, useCallback } from 'react'
import {
  Send, FileText, ChevronDown, ChevronUp, Zap, BookOpen, Loader2,
} from 'lucide-react'
import NavBar from '../components/NavBar'
import { listDocuments, streamQuery } from '../lib/api'
import type { Document, Source } from '../lib/api'
import { cn } from '../lib/utils'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: Source[]
  streaming: boolean
  cached: boolean
}

export default function ChatPage() {
  const [documents, setDocuments] = useState<Document[]>([])
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [isStreaming, setIsStreaming] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    listDocuments()
      .then(docs => setDocuments(docs.filter(d => d.status === 'COMPLETED')))
      .catch(() => {})
  }, [])

  // Auto-scroll to bottom whenever messages update
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = useCallback(async () => {
    const question = input.trim()
    if (!question || isStreaming) return

    setInput('')
    setIsStreaming(true)

    const userMsg: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: question,
      sources: [],
      streaming: false,
      cached: false,
    }

    const assistantId = crypto.randomUUID()
    const assistantMsg: Message = {
      id: assistantId,
      role: 'assistant',
      content: '',
      sources: [],
      streaming: true,
      cached: false,
    }

    setMessages(prev => [...prev, userMsg, assistantMsg])

    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac

    try {
      for await (const event of streamQuery(question, ac.signal)) {
        if (ac.signal.aborted) break

        if (event.name === 'sources') {
          const payload = JSON.parse(event.data) as { chunks: Source[]; cached: boolean }
          setMessages(prev =>
            prev.map(m =>
              m.id === assistantId
                ? { ...m, sources: payload.chunks, cached: payload.cached }
                : m,
            ),
          )
        } else if (event.name === 'token') {
          const { text } = JSON.parse(event.data) as { text: string }
          setMessages(prev =>
            prev.map(m =>
              m.id === assistantId ? { ...m, content: m.content + text } : m,
            ),
          )
        } else if (event.name === 'done') {
          setMessages(prev =>
            prev.map(m => (m.id === assistantId ? { ...m, streaming: false } : m)),
          )
        } else if (event.name === 'error') {
          const { message } = JSON.parse(event.data) as { message: string }
          setMessages(prev =>
            prev.map(m =>
              m.id === assistantId
                ? { ...m, content: message, streaming: false }
                : m,
            ),
          )
        }
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        setMessages(prev =>
          prev.map(m =>
            m.id === assistantId
              ? { ...m, content: 'Something went wrong. Please try again.', streaming: false }
              : m,
          ),
        )
      }
    } finally {
      setIsStreaming(false)
    }
  }, [input, isStreaming])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  // Auto-grow textarea
  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
    const el = e.target
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`
  }

  return (
    <div className="min-h-screen flex flex-col bg-slate-950">
      <NavBar />

      <div className="flex flex-1 overflow-hidden max-w-screen-xl mx-auto w-full">
        {/* ── Sidebar: Document List ────────────────────────────────── */}
        <aside className="hidden lg:flex flex-col w-56 border-r border-slate-800 shrink-0">
          <div className="px-4 py-3 border-b border-slate-800">
            <p className="text-xs font-medium text-slate-400 uppercase tracking-wide">
              Documents
            </p>
          </div>
          <div className="flex-1 overflow-y-auto scrollbar-thin py-2">
            {documents.length === 0 ? (
              <p className="text-xs text-slate-600 px-4 py-3">No documents yet</p>
            ) : (
              documents.map(doc => (
                <div
                  key={doc.id}
                  className="flex items-center gap-2 px-4 py-2 hover:bg-slate-800/60 transition-colors"
                >
                  <FileText className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                  <span className="text-xs text-slate-300 truncate" title={doc.originalFilename}>
                    {doc.originalFilename}
                  </span>
                </div>
              ))
            )}
          </div>
        </aside>

        {/* ── Main: Chat ──────────────────────────────────────────────── */}
        <div className="flex flex-col flex-1 min-h-0">
          {/* Messages */}
          <div className="flex-1 overflow-y-auto scrollbar-thin px-4 md:px-8 py-6 space-y-6">
            {messages.length === 0 && <EmptyState />}

            {messages.map(msg =>
              msg.role === 'user' ? (
                <UserMessage key={msg.id} content={msg.content} />
              ) : (
                <AssistantMessage key={msg.id} message={msg} />
              ),
            )}
            <div ref={bottomRef} />
          </div>

          {/* Input bar */}
          <div className="border-t border-slate-800 bg-slate-900/60 backdrop-blur-sm px-4 md:px-8 py-4">
            <div className="flex items-end gap-3 max-w-3xl mx-auto">
              <div className="flex-1 bg-slate-800 border border-slate-700 rounded-xl px-4 py-3 focus-within:ring-2 focus-within:ring-indigo-500 focus-within:border-transparent transition-shadow">
                <textarea
                  ref={textareaRef}
                  value={input}
                  onChange={handleInput}
                  onKeyDown={handleKeyDown}
                  placeholder="Ask a question about your documents… (Enter to send)"
                  rows={1}
                  disabled={isStreaming}
                  className="w-full bg-transparent text-sm text-slate-200 placeholder-slate-500 resize-none focus:outline-none disabled:opacity-50"
                  style={{ minHeight: '24px', maxHeight: '160px' }}
                />
              </div>
              <button
                onClick={sendMessage}
                disabled={!input.trim() || isStreaming}
                className="w-10 h-10 shrink-0 flex items-center justify-center rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {isStreaming ? (
                  <Loader2 className="w-4 h-4 text-white animate-spin" />
                ) : (
                  <Send className="w-4 h-4 text-white" />
                )}
              </button>
            </div>
            <p className="text-center text-xs text-slate-600 mt-2 max-w-3xl mx-auto">
              Answers are grounded in your uploaded documents · Shift+Enter for new line
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── Sub-components ────────────────────────────────────────────────────────────

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center h-full py-20 text-center">
      <div className="w-12 h-12 rounded-2xl bg-indigo-600/20 flex items-center justify-center mb-4">
        <Zap className="w-6 h-6 text-indigo-400" />
      </div>
      <h3 className="text-lg font-semibold text-slate-200 mb-1">Ask anything</h3>
      <p className="text-sm text-slate-500 max-w-xs">
        Your questions are answered using only the context from your uploaded documents.
      </p>
    </div>
  )
}

function UserMessage({ content }: { content: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[75%] bg-indigo-600 text-white rounded-2xl rounded-tr-sm px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap">
        {content}
      </div>
    </div>
  )
}

function AssistantMessage({ message }: { message: Message }) {
  const [sourcesOpen, setSourcesOpen] = useState(false)

  return (
    <div className="flex justify-start max-w-[85%]">
      <div className="w-full space-y-2">
        {/* Sources */}
        {message.sources.length > 0 && (
          <div className="bg-slate-800/60 border border-slate-700/60 rounded-xl overflow-hidden">
            <button
              onClick={() => setSourcesOpen(v => !v)}
              className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium text-slate-400 hover:text-slate-300 hover:bg-slate-700/40 transition-colors"
            >
              <span className="flex items-center gap-1.5">
                <BookOpen className="w-3.5 h-3.5" />
                {message.sources.length} source{message.sources.length !== 1 && 's'}
                {message.cached && (
                  <span className="ml-1 text-[10px] bg-indigo-500/20 text-indigo-400 px-1.5 py-0.5 rounded border border-indigo-500/30">
                    cached
                  </span>
                )}
              </span>
              {sourcesOpen ? (
                <ChevronUp className="w-3.5 h-3.5" />
              ) : (
                <ChevronDown className="w-3.5 h-3.5" />
              )}
            </button>

            {sourcesOpen && (
              <div className="px-3 pb-3 space-y-2 border-t border-slate-700/60 pt-2">
                {message.sources.map((src, i) => (
                  <SourceCard key={i} source={src} />
                ))}
              </div>
            )}
          </div>
        )}

        {/* Message bubble */}
        <div
          className={cn(
            'bg-slate-900 border rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-slate-200 whitespace-pre-wrap',
            message.streaming ? 'border-slate-700' : 'border-slate-800',
          )}
        >
          {message.content || (message.streaming ? '' : '…')}
          {message.streaming && (
            <span className="inline-block w-0.5 h-4 bg-indigo-400 ml-0.5 animate-blink align-middle" />
          )}
        </div>
      </div>
    </div>
  )
}

function SourceCard({ source }: { source: Source }) {
  return (
    <div className="bg-slate-900/80 border border-slate-700/60 rounded-lg px-3 py-2">
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-medium text-slate-300 truncate max-w-[180px]">
          {source.filename}
        </span>
        <div className="flex items-center gap-2 shrink-0 ml-2">
          <span className="text-[10px] text-slate-500">p.{source.page}</span>
          <span className="text-[10px] text-indigo-400 font-medium">
            {Math.round(source.similarity * 100)}% match
          </span>
        </div>
      </div>
      <p className="text-xs text-slate-500 line-clamp-2">{source.snippet}</p>
    </div>
  )
}
