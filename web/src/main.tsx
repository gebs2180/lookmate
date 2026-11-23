import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
// Self-hosted type (no font CDN at runtime): Instrument Serif for display, Inter Tight for everything read or tapped.
import '@fontsource/instrument-serif/400.css'
import '@fontsource/instrument-serif/400-italic.css'
import '@fontsource-variable/inter-tight/wght.css'
import './index.css'
import './lookbook.css'
import App from './App.tsx'

// A deploy renames the lazy chunks under tabs that are still open; reload once to pick up the new ones.
// The sessionStorage flag stops a loop if the chunk is really gone; without storage we don't reload.
window.addEventListener('vite:preloadError', (event) => {
  try {
    if (sessionStorage.getItem('preload-reloaded')) return
    sessionStorage.setItem('preload-reloaded', '1')
  } catch {
    return
  }
  event.preventDefault()
  window.location.reload()
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
