import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type InvitePreview } from '../api'
import { message } from '../format'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/** A stylist's invite link: accept it while signed in to join their stylist org. */
export function InvitePage() {
  const { token = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: invite, error } = useLoad(() => api<InvitePreview>('GET', apiPath`/api/invites/${token}`), [api, token])
  const [acceptError, setAcceptError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function accept() {
    setAcceptError(null)
    setBusy(true)
    try {
      const { orgId } = await api<{ orgId: string }>('POST', apiPath`/api/invites/${token}/accept`)
      // A stylist goes straight on to the terms and their profile.
      navigate(invite?.orgType === 'STYLIST' ? `/stylist/${orgId}` : '/', { replace: true })
    } catch (err) {
      setAcceptError(message(err))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <PageError message={`This invite link is not valid. ${error}`} />
  if (!invite) return <Loading />

  return (
    <>
      <h1>You're invited to LookMate</h1>
      <p>Join as {invite.orgName}. Next you'll set up your profile and read the stylist terms.</p>
      {invite.usable ? (
        <div className="actions">
          <button onClick={accept} disabled={busy} aria-busy={busy}>
            Accept invite
          </button>
        </div>
      ) : (
        <p className="error" role="alert">This invite has expired or was already used. Ask for a new link.</p>
      )}
      {acceptError && <p className="error" role="alert">{acceptError}</p>}
    </>
  )
}
