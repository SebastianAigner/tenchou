import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createFileRoute, Link } from '@tanstack/react-router'
import { useRef, useState, type FormEvent } from 'react'
import ReactMarkdown from 'react-markdown'
import { getInstructions, publishApp } from '../api'

export const Route = createFileRoute('/publish')({ component: PublishPage })

function PublishPage() {
  const queryClient = useQueryClient()
  const formRef = useRef<HTMLFormElement>(null)
  const [fileName, setFileName] = useState<string>()
  const instructions = useQuery({ queryKey: ['instructions'], queryFn: getInstructions })
  const upload = useMutation({
    mutationFn: publishApp,
    onSuccess: async () => {
      formRef.current?.reset()
      setFileName(undefined)
      await queryClient.invalidateQueries({ queryKey: ['apps'] })
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    upload.mutate(new FormData(event.currentTarget))
  }

  return (
    <>
      <section className="hero compact">
        <h1>Ship a build.</h1>
        <p>Upload an exported, signed IPA. Tenchou handles the manifest and artwork.</p>
      </section>

      <section className="publisher-grid">
        <form className="upload-card" ref={formRef} onSubmit={submit}>
          <div className="card-heading">
            <h2>New release</h2>
            <p>Re-uploading a bundle ID replaces its current build.</p>
          </div>
          <label className="file-drop">
            <input
              type="file"
              name="ipa"
              accept=".ipa,application/octet-stream"
              required
              onChange={(event) => setFileName(event.target.files?.[0]?.name)}
            />
            <span className="file-icon">↑</span>
            <strong>{fileName ?? 'Choose an IPA'}</strong>
            <small>Development or Ad Hoc export</small>
          </label>
          <div className="field-row">
            <label>
              <span>Title <small>optional</small></span>
              <input name="title" type="text" placeholder="Read from the IPA" />
            </label>
            <label>
              <span>Subtitle <small>optional</small></span>
              <input name="subtitle" type="text" placeholder="Version and build by default" />
            </label>
          </div>
          <label>
            <span>Custom artwork <small>optional</small></span>
            <input name="icon" type="file" accept="image/png,image/jpeg" />
          </label>
          <button className="primary-button" disabled={upload.isPending} type="submit">
            {upload.isPending ? 'Uploading…' : 'Publish build'}
          </button>
          {upload.isError && <div className="notice error">{upload.error.message}</div>}
          {upload.isSuccess && (
            <div className="notice success">
              {upload.data.title} is ready. <Link to="/">View apps</Link>
            </div>
          )}
        </form>

        <aside className="instructions-card">
          <div className="card-heading inline">
            <div>
              <h2>Prepare your IPA</h2>
              <p>The exact same guide is available as plain Markdown.</p>
            </div>
            <a href="/instructions.md">Raw guide</a>
          </div>
          {instructions.isPending && <p>Loading instructions…</p>}
          {instructions.isError && <div className="notice error">{instructions.error.message}</div>}
          {instructions.data && <div className="markdown"><ReactMarkdown>{instructions.data}</ReactMarkdown></div>}
        </aside>
      </section>
    </>
  )
}
