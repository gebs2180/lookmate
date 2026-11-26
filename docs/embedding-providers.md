# Embedding providers

**Decided 2026-11-20: Amazon Bedrock, Titan Multimodal Embeddings G1.** The comparison below is kept for the record.

Matching v1 needs an image-embedding model (brief, Must have 4). The code is ready for any provider behind
`EmbeddingProvider` (`backend/app/.../matching`); only a deterministic fake runs now (`lookmate.matching.provider: fake`).
Nothing below is wired or paid for yet: the AWS account is in progress. The decision is recorded in CLAUDE.md ("Defaults to confirm"); what's left is to wire it and set the threshold from the labelled set.

## What matters here

- **Inputs are client face photos.** The provider must not keep them or train on them, and the terms must say so in
  writing (CLAUDE.md, Non-negotiables; docs/data-retention.md, "What the job can't reach"). For BIPA/Texas later, we must be able
  to say where the image went and that it's gone.
- **Quality on textured hair** is the deciding test, not price: run the labelled set of curly and coily looks
  (docs/architecture.md, Matching v1) against each candidate before choosing.
- **Cost barely matters at pilot scale.** 40 stylists × 20 photos + a few hundred requests is about 1,500 images:
  under $1 with any option below. Re-embedding everything for a model change costs the same again.
- **Dimension** sets the pgvector index later (`embedding::vector(<n>)`); any of these is fine for an exact scan now.

## Options

Prices are list prices found on 2026-11-20, partly from third-party aggregators. **Check each on the provider's own
pricing page before deciding.**

| | Amazon Bedrock: Titan Multimodal Embeddings G1 | Google Vertex AI: `multimodalembedding@001` | Cohere Embed v4 (on Bedrock, or Cohere's API) |
|---|---|---|---|
| Price per 1k images | about $0.06 (aggregator listing; not confirmed on AWS's page) | about $0.10 (aggregator; Google bills images as their own SKU) | about $0.20 for a 512×512 image (about 1,600 tokens at $0.12/M tokens; our arithmetic) |
| Dimension | 1024 (256 and 384 optional) | 1408 (128/256/512 optional) | 1536 (smaller Matryoshka sizes optional) |
| Latency | synchronous API, one image per call; tens to a few hundred ms | synchronous, one image per call | synchronous; one image per request |
| Inputs kept or trained on | Bedrock's terms: prompts and inputs aren't stored or used to train models | Google Cloud's terms: customer data isn't used to train without permission. Check abuse-monitoring caching and turn it off | On Bedrock, Bedrock's terms apply. Cohere's own API needs its zero-retention/enterprise terms checked |
| Commercial terms | AWS customer agreement; pay as you go | Google Cloud terms; pay as you go | Bedrock: AWS terms. Cohere direct: Cohere's terms |
| New account? | Yes: we have no AWS account (hosting is Hetzner, storage R2) | Yes: no Google Cloud account | Same as Bedrock, or a Cohere account |
| Notes | Built for image search; text+image in one space (useful for a future style library) | Same | Strongest on documents and mixed media; for photos of hair it's unproven |

Self-hosting SigLIP/CLIP (the other open default) stays an option behind the same interface: no third party sees the
photos, but it needs a GPU or a slow CPU worker, which the single Hetzner VPS doesn't have.

## Recommendation (adopted 2026-11-20)

**Amazon Bedrock with Titan Multimodal Embeddings G1, after a quality check.** It has the clearest no-retention,
no-training terms, it's the cheapest, and its 1024 dimensions suit a later HNSW index. Cohere Embed v4 is reachable
on the same account, so we can compare two models with one contract.

Before turning it on (steps 1 to 3 are still to do; step 4 is done):

1. Confirm price and data terms on AWS's own pages, and choose a US region.
2. Build the labelled set: 20-30 inspiration looks across all four hair types, curly and coily included, each with
   the portfolio photos a stylist would call a match. Compare Titan, Cohere v4 and (if we can run it) SigLIP on it.
   Set `lookmate.matching.ranking.similar-threshold` from the result: thresholds don't carry over between models.
3. Add `BedrockEmbeddingProvider` with `lookmate.matching.provider: bedrock`. It needs AWS credentials in
   `deploy/.env` (not requested yet).
4. ~~Record the decision in CLAUDE.md.~~ Done, 2026-11-20.
5. Build the fairness gate below with the provider, in the same PR or before it. Nothing turns Bedrock on without it.

## Fairness gate (to build with the Bedrock provider)

Noted 2026-11-26 (Zach). CLAUDE.md says matching quality on curly and coily hair is "checked, not assumed". Today the
check is the admin fairness report, which only shows what already happened to real clients. The gate is a check that
can fail before a model, dimension or threshold change goes out. A failing gate means the change doesn't ship.

**What it runs on:** the labelled set from step 2. Each look carries its hair type and the portfolio photos a stylist
would call a match. The photos stay private, like every client photo: they live in the private R2 bucket under their
own prefix, never in the repo or CI logs. The set's index (look IDs, hair types, expected matches) can be a small
JSON file kept with them.

**What it measures, per hair type (straight, wavy, curly, coily):**
- **Hit rate at 5:** the share of looks where at least one expected match ranks in the top 5. This is what a client
  sees.
- **Mean rank of the first expected match**, to show *how* a hair type is failing.
- **No-match rate:** the share of looks where nothing clears `similar-threshold`, so the client would get "No close
  matches yet".

**When it fails** (the floors are placeholders until Zach sets them from the first run):
- any hair type's hit rate at 5 is below a floor (placeholder 70%), or
- curly or coily is more than a set gap (placeholder 10 points) below the best hair type, or
- a hair type with fewer looks than a minimum (placeholder 5) is in the set: too small a sample is reported as
  "not measured", never as a pass.

**How it runs:** an on-demand Gradle task or admin-only job, not on every PR, since it calls the provider and reads
private photos. It runs:
- before turning the provider on;
- before changing the model, the vector dimension or `similar-threshold`;
- after adding to the labelled set.

The embeddings are keyed by model, so a re-run costs cents. Each run writes a numbers-only report (per-hair-type
results, model, threshold, date, set version) to the admin page and to `docs/fairness-runs/`. The results drive
`similar-threshold` (step 2) instead of the 0.8 placeholder.

**Out of scope for the gate:** the live fairness report stays as the after-the-fact check on real clients. A later
alert when its by-hair-type numbers drift apart is a separate piece of work.
