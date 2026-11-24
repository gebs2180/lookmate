/** The client pages (/find, /m/:token, the stylist directory, the service finder): no sign-in, outside the signed-in shell (CLAUDE.md). */
export function isPublicPath(pathname: string) {
  return (
    pathname === '/find' ||
    pathname.startsWith('/m/') ||
    pathname === '/stylists' ||
    pathname === '/consult' ||
    pathname.startsWith('/c/')
  )
}
