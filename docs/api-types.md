# API types: keeping the web app in step with the backend

The web app's API types come from the backend's own description of the API, so a renamed or retyped field fails the
build instead of showing up as a blank screen.

## How it fits together

1. **springdoc** describes the backend's endpoints and records as OpenAPI (`backend/app`, `OpenApiConfig`). The running
   app doesn't serve it (`springdoc.api-docs.enabled: false` in `application.yml`).
2. **`OpenApiSpecTest`** turns it on in a test, reads it as the admin and compares it with `web/openapi.json`. It fails
   when they differ.
3. **openapi-typescript** (7.13.0, run with `npx`, not installed) turns `openapi.json` into `web/src/api.gen.ts`
   (`npm run api:types`).
4. **`web/src/api.ts`** imports the generated schemas (`components['schemas'][...]`) and re-exports them under the
   names the pages use. Where the API can send null, the generated types don't say so (Java has no nullability
   annotations here), so `api.ts` narrows those fields by hand. It is the one place that says which fields can be null.
   A change to a record's shape then fails `tsc` (so `npm run build`) wherever a page uses the old shape.

Schema names are the Java class names with their enclosing classes (`IntakeControllerPhotoView`), since several
controllers can have their own `PhotoView`.

## When you change an API record

```bash
cd backend && ./gradlew test -Dopenapi.update=true
cd web && npm run api:types
```

(On Windows use `.\gradlew.bat` and `npm.cmd`.) Then fix what `npm run build` reports in `api.ts` and the pages.

CI checks both steps: the backend job fails if `openapi.json` is stale, and the web job regenerates `api.gen.ts` and
fails if it differs from the committed file.
