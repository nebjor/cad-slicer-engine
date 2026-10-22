# ADR 0010 — Server-side cart for signed-in customers

**Status:** Accepted (owner request 2026-10-22)
**Date:** 2026-10-22
**Builds on:** ADR 0003 (local order-files volume), ADR 0004 (items placed by model id), ADR 0005 (customer accounts).

## Context

The cart lived only in the browser (localStorage for the lines, IndexedDB for the model
files). A customer who uploaded a model, registered and opened the registration link on the
phone found an empty cart there. Once a customer is signed in, the cart has to belong to the
account so that every device shows it.

The pieces to build on already exist: uploaded models are kept for about an hour under their
content hash (`UploadedModelStore`), orders copy them into durable storage
(`OrderFileStorage` on the order-files volume, which is backed up), and an account is
identified by the `pod_session` cookie.

## Decision

1. **Only for signed-in customers.** Guests keep the browser cart exactly as before; nothing
   about the quote path or guest checkout changes. After signing in, the frontend pushes its
   local cart to the server and from then on treats the server copy as the truth.
2. **The client sends the whole cart.** `PUT /api/account/cart` replaces the stored cart with
   the given lines in the given order (at most 10, the same as `OrderRequest.MAX_ITEMS`, so
   a full cart can always be ordered). A line either names a stored line by `id` (its file is
   kept) or an uploaded model by `modelId` (its file is copied). Lines left out are deleted
   with their files. One idempotent call keeps the frontend free of per-line add, move and
   remove endpoints and of the races between devices they would invite: the last save wins.
3. **Files are copied, not referenced.** A new line copies the model from the one-hour upload
   store into the order-files storage under its own key, one file per line even when two
   lines show the same model. The upload store evicts by time and size, so a reference would
   expire; sharing a file between lines would make deleting one line a reference-counting
   problem. Disk cost is bounded by 10 lines per account. An expired `modelId` answers 404
   `model-expired` with the line index, the problem type the order endpoint uses, and the
   old cart is left as it was.
4. **Other devices get the file back through the upload store.** `POST
   /api/account/cart/{id}/model` re-registers the stored file through the same staging and
   hashing code as `POST /api/models` (`QuoteService.storeUpload(InputStream, name)`) and
   returns a fresh model id, so the device can quote and order the line without holding the
   file.
5. **The quote is opaque.** The line stores the quote the frontend holds as `jsonb` and
   returns it untouched; the backend never reads it, and the order endpoint re-quotes as
   before. A change to the quote schema therefore needs no migration here.
6. **Module boundaries.** The feature lives in `com.slicercore.cart`. It depends on the quote
   module (staging) and on the order module's ports (`OrderFileStorage`, `CurrentAccount`,
   `RetentionSweep`) and not on `account`; the account module calls the cart on account
   deletion, never the reverse (`ArchitectureTest`).
7. **Consistency of files and rows.** Rows change in one transaction. Files that a request
   copied are deleted again if the request fails, and files of removed lines are deleted only
   after the transaction committed, so a failed save never loses a stored file. A crash
   between commit and deletion leaves an orphaned file, never a line without one.

## Data retention

- A cart line is deleted with its file by the nightly retention run (`CartRetentionSweep`
  through the `RetentionSweep` port) when it was last saved more than
  `account.cart-retention-days` ago (default 30). Every `PUT` touches the lines it lists, so
  an active cart does not expire; an abandoned one does.
- Deleting the account deletes the cart and its files first (`AccountService.deleteAccount`);
  `ON DELETE CASCADE` on `account_cart_items.account_id` is the backstop for the rows.
- `DELETE /api/account/cart` empties the cart on request.
- The files are customer designs kept without an order, which is why the retention is
  short and listed in `docs/data-protection.md`.

## Trade-offs

- **Last write wins.** Two devices editing at once overwrite each other. A cart is edited
  by one person, mostly one device at a time, and a merge protocol would cost more than the
  rare lost edit.
- **Copy cost.** Every first save of a line writes the file once. Models are small
  (megabytes) and lines are capped.
- **No guest sync.** A guest who never signs in has no cross-device cart; signing in is the
  step that creates it, which also gives the account a reason to exist.
