import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  FileUp, CheckCircle2, XCircle, Clock, Loader2, FileText, MessageSquare, UploadCloud,
} from 'lucide-react'
import NavBar from '../components/NavBar'
import { uploadDocument, getJobStatus, listDocuments } from '../lib/api'
import type { Document, JobStatus } from '../lib/api'
import { cn, formatBytes } from '../lib/utils'

interface ActiveJob {
  jobId: string
  filename: string
  status: JobStatus['status']
}

const STATUS_ORDER: Record<JobStatus['status'], number> = {
  PENDING: 0, PROCESSING: 1, COMPLETED: 2, FAILED: 2,
}

function jobProgress(status: JobStatus['status']): number {
  return { PENDING: 15, PROCESSING: 60, COMPLETED: 100, FAILED: 100 }[status]
}

export default function UploadPage() {
  const [dragOver, setDragOver] = useState(false)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const [jobs, setJobs] = useState<ActiveJob[]>([])
  const [documents, setDocuments] = useState<Document[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Load existing documents on mount
  useEffect(() => {
    listDocuments()
      .then(setDocuments)
      .catch(() => {})
  }, [])

  // Poll active jobs every 2 s
  useEffect(() => {
    const activeJobs = jobs.filter(j => j.status === 'PENDING' || j.status === 'PROCESSING')
    if (activeJobs.length === 0) return

    const id = setInterval(async () => {
      const updates = await Promise.allSettled(activeJobs.map(j => getJobStatus(j.jobId)))
      let anyCompleted = false

      setJobs(prev =>
        prev.map(job => {
          const result = updates[activeJobs.findIndex(j => j.jobId === job.jobId)]
          if (result?.status === 'fulfilled') {
            const newStatus = result.value.status
            if (newStatus === 'COMPLETED' || newStatus === 'FAILED') anyCompleted = true
            return { ...job, status: newStatus }
          }
          return job
        }),
      )

      if (anyCompleted) {
        listDocuments().then(setDocuments).catch(() => {})
      }
    }, 2000)

    return () => clearInterval(id)
  }, [jobs])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files[0]
    if (file) {
      setSelectedFile(file)
      setUploadError('')
    }
  }, [])

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) {
      setSelectedFile(file)
      setUploadError('')
    }
  }

  const handleUpload = async () => {
    if (!selectedFile) return
    setUploading(true)
    setUploadError('')
    try {
      const res = await uploadDocument(selectedFile)
      setJobs(prev => [
        { jobId: res.jobId, filename: res.originalFilename, status: 'PENDING' },
        ...prev,
      ])
      setSelectedFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const completedDocuments = documents.filter(d => d.status === 'COMPLETED')

  return (
    <div className="min-h-screen flex flex-col bg-slate-950">
      <NavBar />

      <main className="flex-1 max-w-screen-xl mx-auto w-full px-6 py-8">
        <div className="mb-6">
          <h2 className="text-lg font-semibold text-slate-100">Documents</h2>
          <p className="text-sm text-slate-400 mt-0.5">
            Upload PDFs, DOCX, or text files. They'll be processed and made available for chat.
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
          {/* ── Left: Upload + Active Jobs ─────────────────────── */}
          <div className="lg:col-span-2 space-y-4">
            {/* Drop zone */}
            <div
              onDragOver={e => { e.preventDefault(); setDragOver(true) }}
              onDragLeave={() => setDragOver(false)}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
              className={cn(
                'border-2 border-dashed rounded-xl p-8 flex flex-col items-center justify-center cursor-pointer transition-all text-center',
                dragOver
                  ? 'border-indigo-500 bg-indigo-500/10'
                  : selectedFile
                  ? 'border-slate-600 bg-slate-800/60'
                  : 'border-slate-700 bg-slate-900 hover:border-slate-600 hover:bg-slate-800/40',
              )}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.docx,.txt"
                onChange={handleFileChange}
                className="hidden"
              />
              <UploadCloud
                className={cn(
                  'w-10 h-10 mb-3',
                  dragOver ? 'text-indigo-400' : 'text-slate-500',
                )}
              />
              {selectedFile ? (
                <>
                  <p className="text-sm font-medium text-slate-200">{selectedFile.name}</p>
                  <p className="text-xs text-slate-400 mt-1">{formatBytes(selectedFile.size)}</p>
                </>
              ) : (
                <>
                  <p className="text-sm font-medium text-slate-300">
                    {dragOver ? 'Drop to upload' : 'Drop file or click to browse'}
                  </p>
                  <p className="text-xs text-slate-500 mt-1">PDF, DOCX, TXT · max 100 MB</p>
                </>
              )}
            </div>

            {uploadError && (
              <p className="text-xs text-red-400 flex items-center gap-1.5">
                <XCircle className="w-3.5 h-3.5" /> {uploadError}
              </p>
            )}

            <button
              onClick={handleUpload}
              disabled={!selectedFile || uploading}
              className="w-full flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-medium rounded-lg px-4 py-2.5 transition-colors"
            >
              {uploading ? (
                <><Loader2 className="w-4 h-4 animate-spin" /> Uploading…</>
              ) : (
                <><FileUp className="w-4 h-4" /> Upload Document</>
              )}
            </button>

            {/* Active jobs */}
            {jobs.length > 0 && (
              <div className="space-y-2">
                <h3 className="text-xs font-medium text-slate-400 uppercase tracking-wide">
                  Processing Queue
                </h3>
                {[...jobs]
                  .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status])
                  .map(job => (
                    <JobRow key={job.jobId} job={job} />
                  ))}
              </div>
            )}
          </div>

          {/* ── Right: Document Library ─────────────────────────── */}
          <div className="lg:col-span-3">
            <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between">
                <h3 className="text-sm font-medium text-slate-200">
                  Library{completedDocuments.length > 0 && (
                    <span className="ml-2 text-xs text-slate-500">
                      {completedDocuments.length} document{completedDocuments.length !== 1 && 's'}
                    </span>
                  )}
                </h3>
                {completedDocuments.length > 0 && (
                  <Link
                    to="/chat"
                    className="flex items-center gap-1.5 text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
                  >
                    <MessageSquare className="w-3.5 h-3.5" />
                    Start chatting
                  </Link>
                )}
              </div>

              {completedDocuments.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-16 text-center">
                  <FileText className="w-10 h-10 text-slate-700 mb-3" />
                  <p className="text-sm text-slate-500">No documents yet</p>
                  <p className="text-xs text-slate-600 mt-1">
                    Upload a file to get started
                  </p>
                </div>
              ) : (
                <ul className="divide-y divide-slate-800">
                  {completedDocuments.map(doc => (
                    <li key={doc.id} className="flex items-center gap-3 px-4 py-3 hover:bg-slate-800/40 transition-colors">
                      <div className="w-8 h-8 rounded-lg bg-slate-800 flex items-center justify-center shrink-0">
                        <FileText className="w-4 h-4 text-indigo-400" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="text-sm text-slate-200 truncate">{doc.originalFilename}</p>
                        <p className="text-xs text-slate-500 mt-0.5">{formatBytes(doc.fileSizeBytes)}</p>
                      </div>
                      <StatusBadge status={doc.status} />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}

function JobRow({ job }: { job: ActiveJob }) {
  const progress = jobProgress(job.status)
  const isTerminal = job.status === 'COMPLETED' || job.status === 'FAILED'

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-lg px-3 py-2.5">
      <div className="flex items-center justify-between mb-1.5">
        <p className="text-xs text-slate-300 truncate max-w-[200px]">{job.filename}</p>
        <StatusBadge status={job.status} small />
      </div>
      <div className="w-full bg-slate-800 rounded-full h-1">
        <div
          className={cn(
            'h-1 rounded-full transition-all duration-700',
            job.status === 'FAILED' ? 'bg-red-500' :
            job.status === 'COMPLETED' ? 'bg-emerald-500' :
            job.status === 'PROCESSING' ? 'bg-blue-500 animate-pulse' : 'bg-slate-600',
          )}
          style={{ width: `${progress}%` }}
        />
      </div>
      {isTerminal && (
        <p className="text-xs text-slate-500 mt-1">
          {job.status === 'COMPLETED' ? 'Ready for chat' : 'Processing failed'}
        </p>
      )}
    </div>
  )
}

function StatusBadge({ status, small }: { status: string; small?: boolean }) {
  const cfg: Record<string, { cls: string; icon: React.ReactNode; label: string }> = {
    PENDING: {
      cls: 'bg-yellow-400/10 text-yellow-400 border-yellow-400/20',
      icon: <Clock className={small ? 'w-2.5 h-2.5' : 'w-3 h-3'} />,
      label: 'Pending',
    },
    PROCESSING: {
      cls: 'bg-blue-400/10 text-blue-400 border-blue-400/20',
      icon: <Loader2 className={cn(small ? 'w-2.5 h-2.5' : 'w-3 h-3', 'animate-spin')} />,
      label: 'Processing',
    },
    COMPLETED: {
      cls: 'bg-emerald-400/10 text-emerald-400 border-emerald-400/20',
      icon: <CheckCircle2 className={small ? 'w-2.5 h-2.5' : 'w-3 h-3'} />,
      label: 'Ready',
    },
    FAILED: {
      cls: 'bg-red-400/10 text-red-400 border-red-400/20',
      icon: <XCircle className={small ? 'w-2.5 h-2.5' : 'w-3 h-3'} />,
      label: 'Failed',
    },
  }

  const c = cfg[status] ?? cfg.PENDING
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 border rounded-md font-medium',
        small ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-1 text-xs',
        c.cls,
      )}
    >
      {c.icon}
      {c.label}
    </span>
  )
}
