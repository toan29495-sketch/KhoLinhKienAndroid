# Cloud backup verification trust model

Date: 2026-11-04  
Scope: source/catalog review only; no runtime or production changes.

## Current protocol

1. Android prepares a ZIP, calculates its size and SHA-256, and registers immutable owner-scoped metadata with `begin_inventory_backup`.
2. The Storage object is uploaded to the private `inventory-backups` bucket under the caller's UUID folder. The bucket limit is 256 MiB.
3. The official Android uploader downloads the uploaded object through the authenticated client into a temporary file, checks exact byte count and SHA-256, and only then calls `complete_inventory_backup`.
4. The SQL function checks the caller's active profile, locks the caller's metadata row, compares the supplied digest to the registered digest, and confirms that the owner-scoped Storage object exists and its metadata size matches. It does not download or hash object bytes.
5. The row is marked `VERIFIED` with `verification_method='CLIENT_DOWNLOAD_SHA256'`. The Android uploader then records the result locally.
6. On restore, Android independently downloads the object and checks size, SHA-256, ZIP structure, manifest owner, and payload constraints before inventory replacement.

## What this proves — and what it does not

For the official app path, the uploaded bytes were downloaded again by that app and matched the locally prepared ZIP before the completion RPC. A future official restore client checks the bytes again before accepting the archive.

The database does **not** independently attest to the object's SHA-256. An authenticated custom client can register a digest, upload an owner-scoped object of the registered size, then call the completion RPC with that same digest without actually hashing the object. It can therefore cause the metadata status to become `VERIFIED` even if the bytes do not match the digest. This does not grant access to another owner's object or inventory, but it means `VERIFIED` is not a server-side cryptographic attestation.

The current schema's `verification_method='CLIENT_DOWNLOAD_SHA256'` accurately records the trust boundary. The status label by itself is broader than the evidence if read as “server independently verified the bytes.”

## Options

| Option | Integrity assurance | Cost / risk | Compatibility |
|---|---|---|---|
| A. Keep current protocol and explicitly label it client-verified | Official app readback is verified; server trusts authenticated caller's assertion | Lowest operational cost; custom clients can falsely complete their own backup | Existing clients and schema remain unchanged |
| B. Verify in a server-side Edge Function before setting VERIFIED | Server can independently download and hash the private object; caller cannot self-attest | Extra download, CPU, memory, latency and egress; 256 MiB requires a streaming hash implementation and explicit resource-limit testing | Requires new server workflow, authorization, status transition and migration/Edge deployment |
| C. Reduce object size to fit an in-memory server hash | Server-side hash can be simpler | Reintroduces a restrictive archive limit; not appropriate without measured need and user approval | Bucket and app limits would need coordinated change |
| D. Keep server-side verification out of scope, but make the UI and documentation say “đã xác minh bởi ứng dụng” | Honest user-facing semantics while preserving official client integrity checks | Does not prevent a custom client from self-attesting; restore client must continue independent verification | Can be implemented client-side without changing backend contract |

## Recommendation

For this app's current owner-scoped personal inventory model, retain the existing authenticated readback protocol for now, preserve `verification_method='CLIENT_DOWNLOAD_SHA256'`, and make user-facing wording explicit that the official app performed the readback verification. Keep the independent restore-time SHA-256 and ZIP/owner checks mandatory.

Do not silently add an Edge Function or change the bucket cap. If server-attested verification is a hard requirement, design a separate bounded streaming verifier first, test the maximum 256 MiB object and concurrent uploads under actual Supabase Edge resource limits, then obtain explicit approval before changing the backend.

## Required invariants

- Never allow an archive to be restored solely because its row says `VERIFIED`; the client must still download and verify the exact bytes and archive structure.
- Keep backup metadata, Storage paths, listing and downloads owner-scoped.
- Never expose the service-role key to Android.
- Do not increase the archive cap without memory, disk, network and timeout testing.
- Keep the difference between client verification and server verification visible in documentation and UI.
