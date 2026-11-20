import { ApiError, apiPath, publicApi, uploadToStorage, type MatchRequestCreated } from '../../api'

type UploadSlot = MatchRequestCreated['upload']

/** Longest side of the photo we send: plenty for matching, and a phone photo uploads in seconds. */
const MAX_SIDE = 2048

/**
 * Re-draws the photo as a JPEG at most {@link MAX_SIDE} pixels on its long side, which also drops its location and
 * camera metadata before it leaves the phone (the server strips it again). Browsers apply the EXIF orientation when
 * decoding. Where the browser can't do this, a JPEG or PNG goes as it is; anything else (HEIC on an old browser) is
 * refused with words people can act on.
 */
export async function preparePhoto(file: File): Promise<Blob> {
  try {
    if (typeof createImageBitmap === 'function' && typeof document !== 'undefined') {
      const bitmap = await createImageBitmap(file)
      const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(bitmap.width * scale)
      canvas.height = Math.round(bitmap.height * scale)
      const context = canvas.getContext('2d')
      if (context) {
        context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
        bitmap.close()
        const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.88))
        if (blob) return blob
      }
    }
  } catch {
    // fall through to the file as it is
  }
  if (file.type === 'image/jpeg' || file.type === 'image/png') return file
  throw new Error('That photo format won’t work here. Choose a JPEG or PNG, or take a screenshot of it.')
}

/** Uploads the photo to its slot and confirms it; asks for a fresh slot first when there isn't one. */
export async function sendPhoto(token: string, photo: Blob, slot?: UploadSlot) {
  const type = photo.type === 'image/png' ? 'image/png' : 'image/jpeg'
  const target =
    slot ??
    (await publicApi<UploadSlot>('POST', apiPath`/api/public/match-requests/${token}/inspiration`, {
      contentType: type,
      sizeBytes: photo.size,
    }))
  await uploadToStorage({ photoId: '', ...target }, photo)
  try {
    await publicApi('POST', apiPath`/api/public/match-requests/${token}/inspiration/confirm`)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'IMAGE_REJECTED') {
      throw new Error("We couldn't use that photo. Choose a JPEG or PNG and try again.", { cause: e })
    }
    throw e
  }
}
