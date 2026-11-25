import { describe, expect, it } from 'vitest'
import { encodeQr, formatBits, rsDivisor, rsRemainder, type QrCode } from './qr'

/** Reads a code back the way a scanner would: format bits, unmask, zigzag, de-interleave (single block only). */
function readBack(code: QrCode, version: number): { mask: number; codewords: number[] } {
  const { size, modules } = code
  let bits = 0
  for (let i = 0; i <= 5; i++) bits |= (modules[i][8] ? 1 : 0) << i
  bits |= (modules[7][8] ? 1 : 0) << 6
  bits |= (modules[8][8] ? 1 : 0) << 7
  bits |= (modules[8][7] ? 1 : 0) << 8
  for (let i = 9; i < 15; i++) bits |= (modules[8][14 - i] ? 1 : 0) << i
  const mask = ((bits ^ 0x5412) >>> 10) & 7
  expect(formatBits(mask)).toBe(bits)
  const reserved = (x: number, y: number) =>
    x === 6 || y === 6 || (x < 9 && y < 9) || (x >= size - 8 && y < 9) || (x < 9 && y >= size - 8) ||
    (version >= 2 && Math.abs(x - (size - 7)) <= 2 && Math.abs(y - (size - 7)) <= 2)
  const masks = [
    (x: number, y: number) => (x + y) % 2 === 0,
    (_: number, y: number) => y % 2 === 0,
    (x: number) => x % 3 === 0,
    (x: number, y: number) => (x + y) % 3 === 0,
    (x: number, y: number) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
    (x: number, y: number) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x: number, y: number) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
    (x: number, y: number) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ]
  const out: number[] = []
  let cur = 0
  let n = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let v = 0; v < size; v++)
      for (let j = 0; j < 2; j++) {
        const x = right - j
        const y = ((right + 1) & 2) === 0 ? size - 1 - v : v
        if (reserved(x, y)) continue
        cur = (cur << 1) | (modules[y][x] !== masks[mask](x, y) ? 1 : 0)
        if (++n % 8 === 0) {
          out.push(cur)
          cur = 0
        }
      }
  }
  return { mask, codewords: out }
}

describe('qr', () => {
  it('has the standard format strings for level M', () => {
    const known = [
      '101010000010010', '101000100100101', '101111001111100', '101101101001011',
      '100010111111001', '100000011001110', '100111110010111', '100101010100000',
    ]
    known.forEach((s, mask) => expect(formatBits(mask).toString(2).padStart(15, '0')).toBe(s))
  })

  it('computes Reed-Solomon error correction like the standard (HELLO WORLD, 1-M)', () => {
    const data = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17]
    expect(rsRemainder(data, rsDivisor(10))).toEqual([196, 35, 39, 119, 235, 215, 231, 226, 93, 23])
  })

  it('encodes a page link that reads back to the same bytes', () => {
    const text = 'https://combpatible.app/s/ana?via=qr'
    const code = encodeQr(text)
    expect(code.size).toBe(29) // version 3: 36 bytes don't fit in version 2-M
    const { codewords } = readBack(code, 3)
    const data = codewords.slice(0, 44)
    expect(rsRemainder(data, rsDivisor(26))).toEqual(codewords.slice(44, 70))
    expect(data[0] >>> 4).toBe(0b0100)
    const length = ((data[0] & 0xf) << 4) | (data[1] >>> 4)
    expect(length).toBe(text.length)
    const bytes = Array.from({ length }, (_, i) => ((data[1 + i] & 0xf) << 4) | (data[2 + i] >>> 4))
    expect(new TextDecoder().decode(new Uint8Array(bytes))).toBe(text)
  })

  it('draws the three finder patterns', () => {
    const { size, modules } = encodeQr('x')
    for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
      expect(modules[cy][cx]).toBe(true)
      expect(modules[cy][cx + 2]).toBe(false)
      expect(modules[cy][cx + 3]).toBe(true)
    }
  })

  it('grows with the text and refuses what cannot fit', () => {
    expect(encodeQr('a'.repeat(200)).size).toBeGreaterThan(29)
    expect(() => encodeQr('a'.repeat(3000))).toThrow()
  })
})
