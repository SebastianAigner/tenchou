import { useQuery } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'
import { getApps, type AppSummary } from '../api'

export const Route = createFileRoute('/')({ component: CatalogPage })

function CatalogPage() {
  const apps = useQuery({ queryKey: ['apps'], queryFn: getApps })

  return (
    <>
      <section className="hero">
        <h1>Ready when you are.</h1>
        <p>Install development builds directly on your registered iPhone or iPad.</p>
      </section>

      <section aria-labelledby="available-apps">
        <div className="section-heading">
          <h2 id="available-apps">Available apps</h2>
          {apps.data && <span>{apps.data.length}</span>}
        </div>

        {apps.isPending && <AppSkeleton />}
        {apps.isError && <div className="notice error">Couldn’t load apps. {apps.error.message}</div>}
        {apps.data?.length === 0 && (
          <div className="empty-state">
            <div className="empty-glyph">↓</div>
            <h3>No builds yet</h3>
            <p>Upload the first signed IPA from the Publish page.</p>
          </div>
        )}
        <div className="app-list">
          {apps.data?.map((app) => <AppCard app={app} key={app.id} />)}
        </div>
      </section>
    </>
  )
}

function AppCard({ app }: { app: AppSummary }) {
  const [now] = useState(Date.now)
  const expiry = app.signedUntil ? new Date(app.signedUntil) : null
  const days = expiry ? Math.ceil((expiry.getTime() - now) / 86_400_000) : null
  const expiryLabel = expiry
    ? days !== null && days >= 0
      ? `Signed for ${days} ${days === 1 ? 'day' : 'days'}`
      : 'Signature expired'
    : null

  return (
    <article className="app-card">
      <img className="app-icon" src={app.iconUrl} alt="" />
      <div className="app-copy">
        <div>
          <h3>{app.title}</h3>
          <p>{app.subtitle}</p>
        </div>
        <div className="app-meta">
          <span>v{app.version} ({app.build})</span>
          {expiryLabel && (
            <span className={days !== null && days < 7 ? 'expiry urgent' : 'expiry'} title={expiry?.toLocaleString()}>
              {expiryLabel}
            </span>
          )}
        </div>
      </div>
      <a className="install-button" href={app.installUrl}>Install</a>
    </article>
  )
}

function AppSkeleton() {
  return (
    <div className="app-card skeleton" aria-label="Loading apps">
      <div className="app-icon" />
      <div className="app-copy"><span /><span /></div>
    </div>
  )
}
