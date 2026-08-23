import { createRootRoute, Link, Outlet } from '@tanstack/react-router'

export const Route = createRootRoute({
  component: RootLayout,
})

function RootLayout() {
  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="header-inner">
          <Link to="/" className="wordmark" aria-label="Tenchō 店長 home">
            Tenchō 店長
          </Link>
          <nav className="site-nav" aria-label="Main navigation">
            <Link to="/" activeOptions={{ exact: true }} activeProps={{ 'aria-current': 'page' }}>
              Apps
            </Link>
            <Link to="/publish" activeProps={{ 'aria-current': 'page' }}>
              Publish
            </Link>
          </nav>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
      <footer className="site-footer">Private distribution · iOS & iPadOS</footer>
    </div>
  )
}
