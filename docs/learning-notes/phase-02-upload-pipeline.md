# Phase 02 — Creator Upload & Async Processing Pipeline

> **Status:** Done
> **Built:** 2026-09-21
> **Requirement IDs covered:** AUTH-06, PROD-01, PROD-02, PROC-01, PROC-02, PROC-03, PROC-04 (from `docs/requirements.md`)
> **Commits:** Pending

---

## 1. What we built, in plain English

We built the core engine of SecureLeaf: the creator pipeline. Before this phase, users could sign in but couldn't actually upload or sell anything. Now, users can opt-in to become creators and access a dashboard. From there, they can create product listings and upload their digital goods (PDFs). 

Crucially, the PDF is never served directly to buyers. When a creator uploads a PDF, it goes into a raw bucket and triggers an asynchronous processing pipeline. In the background, our server spins up a worker that breaks the PDF down page-by-page, rendering each into an image tile and generating a thumbnail. Only when this intensive process finishes does the product become "LIVE" for buyers.

**Before this phase:** We only had authentication (login/logout).
**After this phase:** Creators can manage their products, upload PDFs, and our backend safely processes those PDFs asynchronously without blocking the user.

---

## 2. Why it matters

This phase implements SecureLeaf's primary value proposition: DRM protection. By converting PDFs into image tiles server-side, we ensure that a buyer can never just right-click and download the original vector PDF file. 

Furthermore, processing PDFs is CPU and memory intensive. If we did this synchronously during the web request, the user's browser would time out, and our web servers would crash under the load. Moving this to an asynchronous background queue ensures the platform remains responsive and stable under load.

---

## 3. New concepts introduced

### 3.1 Asynchronous Processing with `FOR UPDATE SKIP LOCKED`

**What it is:** A database-level lock that allows multiple background worker threads to poll a table for pending jobs and claim them without stepping on each other's toes, effectively turning a standard PostgreSQL table into a high-concurrency message queue.

**The analogy:** Imagine a bucket of tickets in a busy deli. Instead of all the clerks grabbing the exact same top ticket and fighting over it, `SKIP LOCKED` tells a clerk, "Grab the first ticket you see that *nobody else is currently holding*."

**Why we needed it here:** We have background jobs (PDF processing) that can take seconds or minutes. If we scale to multiple instances of our backend, we need to guarantee that two servers don't try to process the exact same PDF at the exact same time.

**How it works:**
1. A user uploads a PDF. A `ProcessingJob` is created with status `QUEUED`.
2. A scheduled worker runs `SELECT ... FROM processing_jobs WHERE status = 'QUEUED' FOR UPDATE SKIP LOCKED LIMIT 1`.
3. The database locks the returned row for this specific transaction. If another worker queries at the exact same time, the DB silently skips the locked row and returns the *next* queued row.
4. The worker updates the status to `PROCESSING` and commits, permanently claiming it.

**In our code:** `backend/src/main/java/com/secureleaf/creator/repository/ProcessingJobRepository.java:18`
```java
@Query(value = """
    SELECT * FROM processing_jobs
    WHERE status = 'QUEUED'
    ORDER BY queued_at ASC
    LIMIT 1
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
Optional<ProcessingJob> findNextJobToProcess();
```

**What breaks without it:** Without `SKIP LOCKED`, two workers would fetch the same `QUEUED` job. One would lock the row, the other would block and wait. When the first finished, the second would wake up and process the *exact same job again* — wasting CPU, duplicating data, and causing race conditions.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| **Separation of API vs DB Models** | Created `CreateProductInput` and `ProductDto` instead of returning the JPA `Product` entity. | Prevents `LazyInitializationException` and avoids leaking database schema structure to the client. | `ProductMapper.java` |
| **Idempotency** | The pipeline deletes existing tiles/thumbnails for a document version before generating new ones. | If a job fails halfway and retries, it won't leave duplicated zombie files in MinIO. | `DocumentProcessingService.java` |
| **Fail-fast Validation** | We inspect the "magic bytes" (`%PDF-`) of the uploaded file instead of just trusting the `.pdf` extension or `Content-Type` header. | Prevents users from uploading malicious scripts or disguised zip files. | `DocumentUploadController.java` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `DocumentUploadController.java` | The REST endpoint that receives the raw file, validates it, saves it to MinIO, and queues the job. |
| `ProcessingJobWorker.java` | A `@Scheduled` cron job that polls the DB for queued jobs and hands them to the pipeline. |
| `DocumentProcessingService.java` | The core 5-stage async state machine (Validate -> Tiles -> Thumbnail -> Preview -> Live). |
| `InMemoryStorageService.java` | A mock storage implementation used exclusively in integration tests to avoid spinning up a real MinIO container. |

**Request trace — `PDF Upload & Processing`:**
1. `UploadProductPage (React)` → POST `/api/products/{id}/document`
2. `DocumentUploadController` → validates magic bytes, uploads to raw MinIO bucket
3. `DocumentUploadController` → creates `DocumentVersion` and `ProcessingJob` (QUEUED)
4. `ProcessingJobWorker` → polls DB, finds job, calls `DocumentProcessingService.processAsync()`
5. `DocumentProcessingService` → loops through stages, updates `ProductStatus` to LIVE when done.

---

## 6. Design decisions and trade-offs

### Decision: Using PostgreSQL for the job queue instead of RabbitMQ/Redis
- **Alternatives considered:** RabbitMQ, Redis, Amazon SQS.
- **Why we chose this:** We already have PostgreSQL for our relational data. Adding a dedicated message broker to the stack adds massive operational complexity for an MVP. Using `SKIP LOCKED` gives us 90% of the queueing capabilities with 0 additional infrastructure.
- **What we gave up:** True pub/sub capabilities, massive scale throughput (Postgres queues degrade if there are millions of unprocessed rows), and delayed retries natively.
- **When we would revisit:** If we see the `processing_jobs` table becoming a bottleneck (e.g., thousands of PDFs uploaded concurrently) or if we separate the workers into an entirely distinct microservice that shouldn't access the primary DB.

---

## 7. Interview questions

### Beginner
**Q: Why don't you return the JPA entity directly from the GraphQL resolver?**
A: Because JPA entities are heavily tied to the database session. If we return an entity and GraphQL tries to access a lazy-loaded relationship outside of the transaction, we get a `LazyInitializationException`. Mapping to a DTO guarantees we fetch exactly what we need while the transaction is open.

### Intermediate
**Q: How do you prevent a user from uploading a virus named `virus.pdf`?**
A: You can't trust the file extension or the `Content-Type` header sent by the browser. In our controller, we read the first few bytes of the file stream (the "magic bytes"). All valid PDFs must start with `%PDF-`. If it doesn't match, we reject it immediately before saving it or passing it to the processing pipeline.

### Advanced / follow-up probes
**Q: Your async pipeline has 5 stages. What happens if the server crashes on stage 3?**
A: The `ProcessingJob` row acts as a state machine. The transaction for the current stage would roll back, and the row remains locked by the dead worker. Eventually, that worker's lease times out (if we implemented a timeout/heartbeat), or the job remains in a STALLED state. In our retry logic, a failed job increments a retry counter and goes back to QUEUED. Because our stages (like generating tiles) are idempotent—they overwrite files based on deterministic keys—the retry will safely pick up where it left off without duplicating data.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Frontend logout redirected but didn't clear backend session | The `useAuth` hook called `client.clearStore()` but didn't actually invoke the GraphQL `logout` mutation with the refresh token. | Added the mutation call and passed the `$refreshToken` variable properly. | Always verify that client-side state clears *and* server-side tokens are invalidated. |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Magic Bytes** | The first few bytes of a file that uniquely identify its actual format, regardless of extension. |
| **Idempotency** | An operation that produces the same result no matter how many times you run it (e.g., `x = 5` is idempotent, `x = x + 1` is not). |

---

## 10. If I had to defend this in a code review

- **Strongest point:** The pipeline is extremely robust for an MVP. Using `FOR UPDATE SKIP LOCKED` guarantees thread safety across multiple instances without adding Redis/RabbitMQ.
- **Strongest point:** Security is tight. File ownership (BOLA) is strictly asserted at every layer, and magic byte validation prevents trivial malicious uploads.
- **Weakest point:** The worker polling interval is fixed. If the queue is empty, we waste DB queries. If the queue is flooded, a fixed cron interval might process them too slowly. Given more time, I'd implement an event-driven wakeup (like Postgres `LISTEN/NOTIFY`) to trigger the worker instantly on upload.
