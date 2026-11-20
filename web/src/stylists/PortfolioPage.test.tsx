import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { respond } from '../test/auth0'
import { PortfolioPage } from './PortfolioPage'
import { renderAt, stubApi } from './testing'
import type { PortfolioPhoto } from './types'

const ORG = 'org-1'

function photo(id: string, over: Partial<PortfolioPhoto> = {}): PortfolioPhoto {
  return {
    id,
    status: 'READY',
    url: `https://storage.test/${id}?signed`,
    contentType: 'image/jpeg',
    width: 64,
    height: 48,
    service: null,
    hairType: null,
    texture: null,
    length: null,
    before: false,
    pairPhotoId: null,
    position: 0,
    createdAt: '2026-11-20T00:00:00Z',
    ...over,
  }
}

describe('PortfolioPage', () => {
  it('uploads a photo: slot, the PUT to storage with the signed headers, then confirm', async () => {
    let photos: PortfolioPhoto[] = []
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/photos`]: () => photos,
      [`POST /api/orgs/${ORG}/photos`]: () =>
        respond(201, { photoId: 'p1', url: 'https://storage.test/put/p1', method: 'PUT', headers: { 'Content-Type': 'image/jpeg', 'If-None-Match': '*' }, expiresAt: '' }),
      'PUT https://storage.test/put/p1': () => respond(200),
      [`POST /api/orgs/${ORG}/photos/p1/confirm`]: () => {
        photos = [photo('p1')]
        return photos[0]
      },
    })
    renderAt(`/stylist/${ORG}/portfolio`, '/stylist/:orgId/portfolio', <PortfolioPage />)
    await screen.findByText('No photos yet')

    const file = new File([new Uint8Array([0xff, 0xd8, 0xff])], 'balayage.jpg', { type: 'image/jpeg' })
    await userEvent.upload(screen.getByLabelText('Choose photos'), file)

    expect(await screen.findByRole('button', { name: 'Tag photo 1, not tagged' })).toBeInTheDocument()
    expect(screen.getByText('balayage.jpg: added')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.url === `/api/orgs/${ORG}/photos`)?.body).toEqual({ contentType: 'image/jpeg', sizeBytes: 3 })
    const put = calls.find((c) => c.method === 'PUT')
    expect(put?.headers).toEqual({ 'Content-Type': 'image/jpeg', 'If-None-Match': '*' })
    expect(screen.getByText(/1 photo needs a service and hair type/)).toBeInTheDocument()
  })

  it('says why an upload was refused', async () => {
    stubApi({
      [`GET /api/orgs/${ORG}/photos`]: () => [],
      [`POST /api/orgs/${ORG}/photos`]: () => respond(409, { detail: 'You have 60 photos, the most a portfolio can hold.', code: 'PHOTO_LIMIT' }),
    })
    renderAt(`/stylist/${ORG}/portfolio`, '/stylist/:orgId/portfolio', <PortfolioPage />)
    await screen.findByText('No photos yet')

    await userEvent.upload(screen.getByLabelText('Choose photos'), new File(['x'], 'a.jpg', { type: 'image/jpeg' }))

    expect(await screen.findByText(/a\.jpg: not added\. You have 60 photos/)).toBeInTheDocument()
  })

  it('tags a photo and pairs it as the before of another', async () => {
    let photos = [photo('p1'), photo('p2', { position: 1, service: 'CUT', hairType: 'CURLY' })]
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/photos`]: () => photos,
      [`PUT /api/orgs/${ORG}/photos/p1/tags`]: (body) => {
        photos = [photo('p1', { service: 'BALAYAGE', hairType: 'COILY', pairPhotoId: 'p2', before: true }), photos[1]]
        return { ...photos[0], ...(body as object) }
      },
    })
    renderAt(`/stylist/${ORG}/portfolio`, '/stylist/:orgId/portfolio', <PortfolioPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Tag photo 1, not tagged' }))
    const sheet = screen.getByRole('dialog', { name: 'Tag this photo' })
    await userEvent.click(within(sheet).getByRole('radio', { name: 'Balayage' }))
    await userEvent.click(within(sheet).getByRole('radio', { name: 'Coily' }))
    await userEvent.click(within(sheet).getByRole('radio', { name: 'Thick' }))
    await userEvent.click(within(sheet).getByRole('radio', { name: 'Pair with photo 1' }))
    await userEvent.click(within(sheet).getByRole('radio', { name: 'This is the before' }))
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save tags' }))

    expect(await screen.findByRole('button', { name: 'Tag photo 1: Balayage · Coily · before' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({
      service: 'BALAYAGE',
      hairType: 'COILY',
      texture: 'THICK',
      length: null,
      before: true,
      pairPhotoId: 'p2',
    })
  })

  it('moves a photo later by sending the whole new order', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/photos`]: () => [photo('p1'), photo('p2'), photo('p3')],
      [`PUT /api/orgs/${ORG}/photos/order`]: () => [],
    })
    renderAt(`/stylist/${ORG}/portfolio`, '/stylist/:orgId/portfolio', <PortfolioPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Move photo 1 later' }))

    expect(screen.getByRole('button', { name: 'Move photo 1 earlier' })).toBeDisabled()
    await screen.findAllByRole('button', { name: /Tag photo/ })
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ photoIds: ['p2', 'p1', 'p3'] })
  })
})
