import type { HairType } from '../stylists/types'
import type { DemoPhoto, DemoStylist } from './demoStylists'

const BACKGROUND: Record<HairType, [string, string]> = {
  STRAIGHT: ['#d9c3a5', '#8a6a4a'],
  WAVY: ['#c99b6d', '#6e4524'],
  CURLY: ['#a86b3c', '#4a2a14'],
  COILY: ['#6b4026', '#24140a'],
}

/** A 900px square JPEG with the photo's caption and tags: a stand-in until real portfolio photos are picked. */
export async function drawPlaceholder(photo: DemoPhoto, stylist: DemoStylist): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = 900
  canvas.height = 900
  const g = canvas.getContext('2d')
  if (!g) throw new Error("This browser can't draw placeholder photos")
  const [light, dark] = BACKGROUND[photo.hairType]
  const fill = g.createLinearGradient(0, 0, 900, 900)
  fill.addColorStop(0, light)
  fill.addColorStop(1, dark)
  g.fillStyle = fill
  g.fillRect(0, 0, 900, 900)
  g.fillStyle = 'rgb(255 255 255 / 0.92)'
  g.textAlign = 'center'
  g.font = 'bold 56px system-ui, sans-serif'
  g.fillText(photo.caption, 450, 400, 820)
  g.font = '40px system-ui, sans-serif'
  const tags = [photo.service, photo.hairType, photo.texture, photo.length].map((t) => t.replaceAll('_', ' ').toLowerCase()).join(' · ')
  g.fillText(tags, 450, 480, 820)
  g.font = '32px system-ui, sans-serif'
  g.fillText(`${stylist.displayName} · sample photo`, 450, 820, 820)
  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('Could not draw a placeholder photo'))), 'image/jpeg', 0.85),
  )
}
