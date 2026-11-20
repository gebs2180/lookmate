# Embedding providers (for the team to decide)

Matching v1 needs an image-embedding model (brief, Must have 4). The code is ready for any provider behind
`EmbeddingProvider` (`backend/app/.../matching`); only a deterministic fake runs now (`lookmate.matching.provider: fake`).
Nothing below is wired or paid for. This is the open default in CLAUDE.md ("Embedding provider and model").

## What matters here

- **Inputs are client face photos.** The provider must not keep them or train on them, and the terms must say so in
  writing (CLAUDE.md, Privacy; docs/data-retention.md, "What the job can't reach"). For BIPA/Texas later, we must be able
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

## Recommendation

**Amazon Bedrock with Titan Multimodal Embeddings G1, after a quality check.** It has the clearest no-retention,
no-training terms, it's the cheapest, and its 1024 dimensions suit a later HNSW index. Cohere Embed v4 is reachable
on the same account, so we can compare two models with one contract.

Before turning it on:

1. Confirm price and data terms on AWS's own pages, and choose a US region.
2. Build the labelled set: 20-30 inspiration looks across all four hair types, curly and coily included, each with
   the portfolio photos a stylist would call a match. Compare Titan, Cohere v4 and (if we can run it) SigLIP on it.
   Set `lookmate.matching.ranking.similar-threshold` from the result: thresholds don't carry over between models.
3. Add `BedrockEmbeddingProvider` with `lookmate.matching.provider: bedrock`. It needs AWS credentials in
   `deploy/.env` (not requested yet).
4. Record the decision in CLAUDE.md.
